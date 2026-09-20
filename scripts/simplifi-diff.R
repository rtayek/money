#!/usr/bin/env Rscript

# Compare two Quicken Simplifi CSV exports.
#
# Usage:
#   Rscript scripts/simplifi-diff.R BEFORE.csv AFTER.csv [OUTPUT-DIRECTORY]
#
# Uses only base R. Outputs canonical sorted copies and a compact change report.

args <- commandArgs(trailingOnly = TRUE)
if (length(args) < 2 || length(args) > 3) {
    stop(
        "Usage: Rscript scripts/simplifi-diff.R BEFORE.csv AFTER.csv [OUTPUT-DIRECTORY]",
        call. = FALSE
    )
}

before_path <- args[[1]]
after_path <- args[[2]]
output_dir <- if (length(args) == 3) args[[3]] else "reports"

if (!file.exists(before_path)) stop("File not found: ", before_path, call. = FALSE)
if (!file.exists(after_path)) stop("File not found: ", after_path, call. = FALSE)
dir.create(output_dir, recursive = TRUE, showWarnings = FALSE)

read_simplifi <- function(path) {
    data <- read.csv(
        path,
        check.names = FALSE,
        stringsAsFactors = FALSE,
        na.strings = character(),
        fileEncoding = "UTF-8-BOM"
    )
    required <- c("Date", "Account", "Payee", "Amount")
    missing <- setdiff(required, names(data))
    if (length(missing) > 0) {
        stop(
            path, " is missing required column(s): ",
            paste(missing, collapse = ", "),
            call. = FALSE
        )
    }
    data[] <- lapply(data, function(column) {
        if (is.character(column)) trimws(column) else column
    })
    data
}

column_or_blank <- function(data, name) {
    if (name %in% names(data)) {
        value <- as.character(data[[name]])
        value[is.na(value)] <- ""
        value
    } else {
        rep("", nrow(data))
    }
}

normalized <- function(value) {
    tolower(trimws(ifelse(is.na(value), "", as.character(value))))
}

parse_dates <- function(value, source_name) {
    value <- trimws(as.character(value))
    parsed <- as.Date(rep(NA_character_, length(value)))
    formats <- c(
        "%b %d, %Y", "%B %d, %Y",
        "%m/%d/%Y", "%m/%d/%y", "%Y-%m-%d"
    )
    for (format_string in formats) {
        positions <- which(is.na(parsed) & nzchar(value))
        if (length(positions) == 0) break
        attempt <- suppressWarnings(as.Date(value[positions], format = format_string))
        good <- !is.na(attempt)
        parsed[positions[good]] <- attempt[good]
    }
    bad <- which(nzchar(value) & is.na(parsed))
    if (length(bad) > 0) {
        examples <- paste(head(unique(value[bad]), 5), collapse = ", ")
        stop("Could not parse Date value(s) in ", source_name, ": ", examples,
             call. = FALSE)
    }
    parsed
}

parse_amounts <- function(value, source_name) {
    value <- trimws(as.character(value))
    negative_parentheses <- grepl("^\\(.*\\)$", value)
    cleaned <- gsub("[,$()[:space:]]", "", value)
    parsed <- suppressWarnings(as.numeric(cleaned))
    parsed[negative_parentheses & !is.na(parsed)] <-
        -abs(parsed[negative_parentheses & !is.na(parsed)])
    bad <- which(nzchar(value) & is.na(parsed))
    if (length(bad) > 0) {
        examples <- paste(head(unique(value[bad]), 5), collapse = ", ")
        stop("Could not parse Amount value(s) in ", source_name, ": ", examples,
             call. = FALSE)
    }
    parsed
}

canonical_sort <- function(data, source_name) {
    date_key <- as.numeric(parse_dates(column_or_blank(data, "Date"), source_name))
    amount_key <- parse_amounts(column_or_blank(data, "Amount"), source_name)
    complete_row_key <- apply(data, 1, function(row) {
        paste(ifelse(is.na(row), "", row), collapse = "\u001e")
    })

    sort_order <- order(
        date_key,
        normalized(column_or_blank(data, "Account")),
        amount_key,
        normalized(column_or_blank(data, "Payee")),
        normalized(column_or_blank(data, "Check #")),
        normalized(column_or_blank(data, "Category")),
        normalized(column_or_blank(data, "Exclusion")),
        normalized(column_or_blank(data, "Reviewed")),
        complete_row_key,
        na.last = TRUE,
        method = "radix"
    )
    data[sort_order, , drop = FALSE]
}

transaction_ids <- function(data, source_name) {
    date_value <- parse_dates(column_or_blank(data, "Date"), source_name)
    amount_value <- parse_amounts(column_or_blank(data, "Amount"), source_name)
    amount_text <- ifelse(is.na(amount_value), "", sprintf("%.2f", amount_value))
    date_text <- ifelse(is.na(date_value), "", format(date_value, "%Y-%m-%d"))

    base_id <- paste(
        date_text,
        normalized(column_or_blank(data, "Account")),
        amount_text,
        normalized(column_or_blank(data, "Payee")),
        normalized(column_or_blank(data, "Check #")),
        sep = "\u001f"
    )
    occurrence <- ave(seq_along(base_id), base_id, FUN = seq_along)
    paste(base_id, occurrence, sep = "\u001d")
}

field_at <- function(data, row_number, name) {
    if (is.na(row_number) || !(name %in% names(data))) return("")
    value <- as.character(data[[name]][[row_number]])
    if (is.na(value)) "" else value
}

change_report <- function(before, after, before_name, after_name) {
    before_ids <- transaction_ids(before, before_name)
    after_ids <- transaction_ids(after, after_name)
    all_ids <- union(before_ids, after_ids)
    rows <- list()

    for (id in all_ids) {
        before_row <- match(id, before_ids)
        after_row <- match(id, after_ids)

        if (is.na(before_row)) {
            change <- "Added"
        } else if (is.na(after_row)) {
            change <- "Removed"
        } else {
            tracked <- c("Category", "Exclusion", "Reviewed")
            different <- vapply(tracked, function(name) {
                normalized(field_at(before, before_row, name)) !=
                    normalized(field_at(after, after_row, name))
            }, logical(1))
            if (!any(different)) next
            change <- "Changed"
        }

        preferred <- if (!is.na(after_row)) after else before
        preferred_row <- if (!is.na(after_row)) after_row else before_row
        rows[[length(rows) + 1]] <- data.frame(
            Change = change,
            Date = field_at(preferred, preferred_row, "Date"),
            Account = field_at(preferred, preferred_row, "Account"),
            Amount = field_at(preferred, preferred_row, "Amount"),
            Payee = field_at(preferred, preferred_row, "Payee"),
            `Check #` = field_at(preferred, preferred_row, "Check #"),
            `Before Category` = field_at(before, before_row, "Category"),
            `After Category` = field_at(after, after_row, "Category"),
            `Before Exclusion` = field_at(before, before_row, "Exclusion"),
            `After Exclusion` = field_at(after, after_row, "Exclusion"),
            `Before Reviewed` = field_at(before, before_row, "Reviewed"),
            `After Reviewed` = field_at(after, after_row, "Reviewed"),
            check.names = FALSE,
            stringsAsFactors = FALSE
        )
    }

    columns <- c(
        "Change", "Date", "Account", "Amount", "Payee", "Check #",
        "Before Category", "After Category",
        "Before Exclusion", "After Exclusion",
        "Before Reviewed", "After Reviewed"
    )
    if (length(rows) == 0) {
        empty <- as.data.frame(
            setNames(replicate(length(columns), character(), simplify = FALSE), columns),
            check.names = FALSE,
            stringsAsFactors = FALSE
        )
        return(empty)
    }

    result <- do.call(rbind, rows)
    report_dates <- parse_dates(result[["Date"]], "change report")
    result[order(report_dates, result[["Change"]], result[["Account"]],
                 result[["Payee"]], na.last = TRUE, method = "radix"), ,
           drop = FALSE]
}

before <- canonical_sort(read_simplifi(before_path), before_path)
after <- canonical_sort(read_simplifi(after_path), after_path)
changes <- change_report(before, after, before_path, after_path)

before_output <- file.path(output_dir, "simplifi-before-sorted.csv")
after_output <- file.path(output_dir, "simplifi-after-sorted.csv")
changes_output <- file.path(output_dir, "simplifi-changes.csv")

write.csv(before, before_output, row.names = FALSE, na = "", fileEncoding = "UTF-8")
write.csv(after, after_output, row.names = FALSE, na = "", fileEncoding = "UTF-8")
write.csv(changes, changes_output, row.names = FALSE, na = "", fileEncoding = "UTF-8")

cat("Wrote ", nrow(before), " sorted before rows to ", before_output, "\n", sep = "")
cat("Wrote ", nrow(after), " sorted after rows to ", after_output, "\n", sep = "")
cat("Wrote ", nrow(changes), " transaction changes to ", changes_output, "\n", sep = "")
