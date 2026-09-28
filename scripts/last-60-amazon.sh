Rscript -e '
x <- read.csv("reports/amazon-action-items.csv", check.names=FALSE)
x$`Simplifi Date` <- as.Date(x$`Simplifi Date`)
x <- x[x$`Simplifi Date` >= Sys.Date()-60, ]
x <- x[order(x$`Simplifi Date`, decreasing=TRUE),
 c("Simplifi Date","Payee","Charge Amount","Current Category",
   "Amazon Product","Recommended Category","Assessment")]
print(x, row.names=FALSE)
'
