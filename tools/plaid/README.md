# tools/plaid

Archived source for `PullTransactions.java`, kept here for version control in
a repo I own. It is NOT part of the `money` module build (it needs the Plaid
client libraries, which this project does not depend on).

## What it does

Pulls every transaction for one Plaid access token via /transactions/sync,
including the raw bank descriptor (the "GOOGLE*MERGE DRAGONS..." text that
Simplifi strips). Each row is tagged with AccountId and a name+mask Account
label, so one login's multiple cards stay separable and several logins can be
combined. Writes to a CSV named by the first argument (default
plaid-transactions.csv).

## Where it builds and runs

Package `com.plaid.quickstart`. It compiles and runs inside the Plaid
Quickstart Java project (which has plaid-java, okhttp, retrofit, and gson on
its classpath):

    ../../../plaid/quickstart/java

Build and run there:

    cd <plaid-quickstart>/java
    mvn clean package
    env $(cat .env | grep -v '#' | xargs) \
      java -cp target/quickstart-1.0-SNAPSHOT.jar com.plaid.quickstart.PullTransactions out.csv

One access token = one login = one file. Swap PLAID_ACCESS_TOKEN in .env per
login and give each pull its own output filename.

This copy is the canonical source; the Quickstart clone (origin is Plaid's
public upstream, so it cannot be pushed) holds the build-able copy.
