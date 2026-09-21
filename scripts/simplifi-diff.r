#!/usr/bin/env Rscript

# Compare two Quicken Simplifi CSV exports.
#
# Usage:
#   Rscript scripts/simplifi-diff.r BEFORE.csv AFTER.csv [OUTPUT-DIRECTORY]
#
# Uses only base R. Outputs canonical sorted copies and a compact change report.
# The comparison removes known mirrored-account duplicates and recognizes
# transactions whose posting date shifts by up to three days.

args <- commandArgs(trailingOnly = TRUE)
if (length(args) < 2 || length(args) > 3) {
    stop(
        "Usage: Rscript scripts/simplifi-diff.r BEFORE.csv AFTER.csv [OUTPUT-DIRECTORY]",
        call. = FALSE
    )
}

before_path <- args[[1]]
after_path <- args[[2]]
output_dir <- if (length(args) == 3) args[[3]] else "reports"

if (!file.exists(before_path)) stop("File not found: ", before_path, call. = FALSE)
if (!file.exists(after_path)) stop("File not found: ", after_path, call. = FALSE)
dir.create(output_dir, recursive = TRUE, showWarnings = FALSE)

# The right-hand account is retained when both accounts contain the same
# transaction.
mirror_accounts <- c(
    "American Express® Traditional Gold" = "Traditional Gold Card"
)

report_columns <- c(
    "Change", "Notes",
    "Before Date", "After Date",
    "Before Account", "After Account",
    "Before Amount", "After Amount",
    "Before Payee", "After Payee",
    "Check #",
    "Before Category", "After Category",
    "Before Exclusion", "After Exclusion",
    "Before Reviewed", "After Reviewed"
)

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

canonical_accounts <- function(value) {
    result <- as.character(value)
    for (alias in names(mirror_accounts)) {
        result[normalized(result) == normalized(alias)] <- unname(mirror_accounts[[alias]])
    }
    result
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
        normalized(canonical_accounts(column_or_blank(data, "Account"))),
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

transaction_signature <- function(data, source_name, include_account = TRUE) {
    date_value <- parse_dates(column_or_blank(data, "Date"), source_name)
    amount_value <- parse_amounts(column_or_blank(data, "Amount"), source_name)
    pieces <- list(
        ifelse(is.na(date_value), "", format(date_value, "%Y-%m-%d")),
        ifelse(is.na(amount_value), "", sprintf("%.2f", amount_value)),
        normalized(column_or_blank(data, "Payee")),
        normalized(column_or_blank(data, "Check #"))
    )
    if (include_account) {
        pieces <- append(
            list(normalized(canonical_accounts(column_or_blank(data, "Account")))),
            pieces
        )
    }
    do.call(paste, c(pieces, sep = "\u001f"))
}

deduplicate_mirrors <- function(data, source_name) {
    remove_rows <- integer()
    accounts <- normalized(column_or_blank(data, "Account"))
    signatures <- transaction_signature(data, source_name, include_account = FALSE)

    for (alias in names(mirror_accounts)) {
        primary <- unname(mirror_accounts[[alias]])
        alias_rows <- which(accounts == normalized(alias))
        primary_rows <- which(accounts == normalized(primary))
        if (length(alias_rows) == 0 || length(primary_rows) == 0) next

        alias_groups <- split(alias_rows, signatures[alias_rows])
        primary_counts <- table(signatures[primary_rows])
        common <- intersect(names(alias_groups), names(primary_counts))

        for (signature in common) {
            count <- min(length(alias_groups[[signature]]),
                         as.integer(primary_counts[[signature]]))
            if (count > 0) {
                remove_rows <- c(remove_rows, head(alias_groups[[signature]], count))
            }
        }
    }

    remove_rows <- unique(remove_rows)
    kept <- if (length(remove_rows) == 0) data else data[-remove_rows, , drop = FALSE]
    list(data = kept, dropped = length(remove_rows))
}

match_transactions <- function(before, after, before_name, after_name) {
    before_count <- nrow(before)
    after_count <- nrow(after)
    before_to_after <- rep(NA_integer_, before_count)
    after_matched <- rep(FALSE, after_count)

    before_dates <- as.numeric(parse_dates(column_or_blank(before, "Date"), before_name))
    after_dates <- as.numeric(parse_dates(column_or_blank(after, "Date"), after_name))
    before_amounts <- parse_amounts(column_or_blank(before, "Amount"), before_name)
    after_amounts <- parse_amounts(column_or_blank(after, "Amount"), after_name)
    before_accounts <- normalized(canonical_accounts(column_or_blank(before, "Account")))
    after_accounts <- normalized(canonical_accounts(column_or_blank(after, "Account")))
    before_payees <- normalized(column_or_blank(before, "Payee"))
    after_payees <- normalized(column_or_blank(after, "Payee"))
    before_checks <- normalized(column_or_blank(before, "Check #"))
    after_checks <- normalized(column_or_blank(after, "Check #"))

    # Exact identity deliberately excludes mutable category/review fields.
    before_keys <- transaction_signature(before, before_name, include_account = TRUE)
    after_keys <- transaction_signature(after, after_name, include_account = TRUE)
    before_groups <- split(seq_len(before_count), before_keys)
    after_groups <- split(seq_len(after_count), after_keys)

    for (key in intersect(names(before_groups), names(after_groups))) {
        before_rows <- before_groups[[key]]
        after_rows <- after_groups[[key]]
        pair_count <- min(length(before_rows), length(after_rows))
        if (pair_count == 0) next
        before_rows <- head(before_rows, pair_count)
        after_rows <- head(after_rows, pair_count)
        before_to_after[before_rows] <- after_rows
        after_matched[after_rows] <- TRUE
    }

    # Pair pending-to-posted transactions with the same account, payee, and
    # check number. Allow three days and a one-dollar posting adjustment.
    candidates <- list()
    for (before_row in which(is.na(before_to_after))) {
        after_rows <- which(
            !after_matched &
            after_accounts == before_accounts[[before_row]] &
            after_payees == before_payees[[before_row]] &
            after_checks == before_checks[[before_row]]
        )
        if (length(after_rows) == 0) next

        day_difference <- abs(after_dates[after_rows] - before_dates[[before_row]])
        amount_difference <- abs(after_amounts[after_rows] - before_amounts[[before_row]])
        good <- !is.na(day_difference) & !is.na(amount_difference) &
            day_difference <= 3 & amount_difference <= 1.00
        if (!any(good)) next

        candidates[[length(candidates) + 1]] <- data.frame(
            before = before_row,
            after = after_rows[good],
            day_difference = day_difference[good],
            amount_difference = amount_difference[good],
            stringsAsFactors = FALSE
        )
    }

    if (length(candidates) > 0) {
        candidates <- do.call(rbind, candidates)
        candidates <- candidates[order(
            candidates$amount_difference,
            candidates$day_difference,
            candidates$before,
            candidates$after
        ), , drop = FALSE]

        for (row in seq_len(nrow(candidates))) {
            before_row <- candidates$before[[row]]
            after_row <- candidates$after[[row]]
            if (is.na(before_to_after[[before_row]]) && !after_matched[[after_row]]) {
                before_to_after[[before_row]] <- after_row
                after_matched[[after_row]] <- TRUE
            }
        }
    }

    # Recognize an unambiguous payee rename when date and amount still identify
    # exactly one transaction on each side.
    for (before_row in which(is.na(before_to_after))) {
        after_rows <- which(
            !after_matched &
            after_accounts == before_accounts[[before_row]] &
            after_checks == before_checks[[before_row]] &
            abs(after_dates - before_dates[[before_row]]) <= 1 &
            abs(after_amounts - before_amounts[[before_row]]) < 0.005
        )
        if (length(after_rows) != 1) next
        after_row <- after_rows[[1]]

        possible_before <- which(
            is.na(before_to_after) &
            before_accounts == after_accounts[[after_row]] &
            before_checks == after_checks[[after_row]] &
            abs(before_dates - after_dates[[after_row]]) <= 1 &
            abs(before_amounts - after_amounts[[after_row]]) < 0.005
        )
        if (length(possible_before) == 1) {
            before_to_after[[before_row]] <- after_row
            after_matched[[after_row]] <- TRUE
        }
    }

    list(before_to_after = before_to_after, after_matched = after_matched)
}

field_at <- function(data, row_number, name) {
    if (is.na(row_number) || !(name %in% names(data))) return("")
    value <- as.character(data[[name]][[row_number]])
    if (is.na(value)) "" else value
}

amount_at <- function(data, row_number, source_name) {
    if (is.na(row_number)) return(NA_real_)
    parse_amounts(field_at(data, row_number, "Amount"), source_name)[[1]]
}

changed_fields <- function(before, after, before_row, after_row,
                           before_name, after_name) {
    changed <- character()

    before_date <- parse_dates(field_at(before, before_row, "Date"), before_name)[[1]]
    after_date <- parse_dates(field_at(after, after_row, "Date"), after_name)[[1]]
    if (!identical(before_date, after_date)) changed <- c(changed, "Date")

    before_account <- normalized(canonical_accounts(
        field_at(before, before_row, "Account")
    ))
    after_account <- normalized(canonical_accounts(
        field_at(after, after_row, "Account")
    ))
    if (before_account != after_account) changed <- c(changed, "Account")

    before_amount <- amount_at(before, before_row, before_name)
    after_amount <- amount_at(after, after_row, after_name)
    if (is.na(before_amount) != is.na(after_amount) ||
        (!is.na(before_amount) && abs(before_amount - after_amount) >= 0.005)) {
        changed <- c(changed, "Amount")
    }

    fields <- c("Payee", "Check #", "Category", "Exclusion", "Reviewed")
    for (name in fields) {
        if (normalized(field_at(before, before_row, name)) !=
            normalized(field_at(after, after_row, name))) {
            changed <- c(changed, name)
        }
    }
    changed
}

empty_change_report <- function() {
    result <- as.data.frame(
        setNames(replicate(length(report_columns), character(), simplify = FALSE),
                 report_columns),
        check.names = FALSE,
        stringsAsFactors = FALSE
    )
    result[["Before Amount"]] <- numeric()
    result[["After Amount"]] <- numeric()
    result
}

report_row <- function(change, notes, before, after, before_row, after_row,
                       before_name, after_name) {
    result <- data.frame(
        change,
        notes,
        field_at(before, before_row, "Date"),
        field_at(after, after_row, "Date"),
        field_at(before, before_row, "Account"),
        field_at(after, after_row, "Account"),
        amount_at(before, before_row, before_name),
        amount_at(after, after_row, after_name),
        field_at(before, before_row, "Payee"),
        field_at(after, after_row, "Payee"),
        if (!is.na(after_row)) field_at(after, after_row, "Check #")
            else field_at(before, before_row, "Check #"),
        field_at(before, before_row, "Category"),
        field_at(after, after_row, "Category"),
        field_at(before, before_row, "Exclusion"),
        field_at(after, after_row, "Exclusion"),
        field_at(before, before_row, "Reviewed"),
        field_at(after, after_row, "Reviewed"),
        check.names = FALSE,
        stringsAsFactors = FALSE
    )
    names(result) <- report_columns
    result
}

change_report <- function(before, after, before_name, after_name) {
    matching <- match_transactions(before, after, before_name, after_name)
    rows <- list()

    for (before_row in seq_len(nrow(before))) {
        after_row <- matching$before_to_after[[before_row]]
        if (is.na(after_row)) {
            rows[[length(rows) + 1]] <- report_row(
                "Removed", "No longer present",
                before, after, before_row, NA_integer_, before_name, after_name
            )
        } else {
            fields <- changed_fields(
                before, after, before_row, after_row, before_name, after_name
            )
            if (length(fields) == 0) next
            rows[[length(rows) + 1]] <- report_row(
                "Changed", paste(fields, collapse = ", "),
                before, after, before_row, after_row, before_name, after_name
            )
        }
    }

    for (after_row in which(!matching$after_matched)) {
        rows[[length(rows) + 1]] <- report_row(
            "Added", "New transaction",
            before, after, NA_integer_, after_row, before_name, after_name
        )
    }

    if (length(rows) == 0) return(empty_change_report())

    result <- do.call(rbind, rows)
    preferred_date <- ifelse(
        nzchar(result[["After Date"]]),
        result[["After Date"]],
        result[["Before Date"]]
    )
    report_dates <- parse_dates(preferred_date, "change report")
    result[order(
        report_dates,
        result[["Change"]],
        normalized(ifelse(
            nzchar(result[["After Account"]]),
            result[["After Account"]],
            result[["Before Account"]]
        )),
        normalized(ifelse(
            nzchar(result[["After Payee"]]),
            result[["After Payee"]],
            result[["Before Payee"]]
        )),
        na.last = TRUE,
        method = "radix"
    ), , drop = FALSE]
}

before_raw <- read_simplifi(before_path)
after_raw <- read_simplifi(after_path)

# Sorted copies preserve every exported row. Only the comparison report removes
# mirrored copies.
before_sorted <- canonical_sort(before_raw, before_path)
after_sorted <- canonical_sort(after_raw, after_path)

before_deduplication <- deduplicate_mirrors(before_raw, before_path)
after_deduplication <- deduplicate_mirrors(after_raw, after_path)
before_compare <- canonical_sort(before_deduplication$data, before_path)
after_compare <- canonical_sort(after_deduplication$data, after_path)

changes <- change_report(before_compare, after_compare, before_path, after_path)

before_output <- file.path(output_dir, "simplifi-before-sorted.csv")
after_output <- file.path(output_dir, "simplifi-after-sorted.csv")
changes_output <- file.path(output_dir, "simplifi-changes.csv")

write.csv(
    before_sorted, before_output,
    row.names = FALSE, na = "", fileEncoding = "UTF-8"
)
write.csv(
    after_sorted, after_output,
    row.names = FALSE, na = "", fileEncoding = "UTF-8"
)
write.csv(
    changes, changes_output,
    row.names = FALSE, na = "", fileEncoding = "UTF-8"
)

summary_counts <- table(factor(
    changes[["Change"]],
    levels = c("Added", "Removed", "Changed")
))

cat("Wrote ", nrow(before_sorted), " sorted before rows to ",
    before_output, "\n", sep = "")
cat("Wrote ", nrow(after_sorted), " sorted after rows to ",
    after_output, "\n", sep = "")
cat("Ignored ", before_deduplication$dropped,
    " mirrored rows from the before export and ",
    after_deduplication$dropped,
    " from the after export.\n", sep = "")
cat("Changes: ",
    summary_counts[["Added"]], " added, ",
    summary_counts[["Removed"]], " removed, ",
    summary_counts[["Changed"]], " changed.\n", sep = "")
cat("Wrote ", nrow(changes), " transaction changes to ",
    changes_output, "\n", sep = "")
