const assert = require("node:assert/strict");
const matcher = require("./matcher-core.js");

const records = matcher.recordsFromCsv('Date,Account,Payee,Amount,Notes\n"10/13/2026","Card 1234","Google","-$19.99","recommend Entertainment"\n');
assert.equal(records.length, 1);
assert.equal(records[0].Payee, "Google");
assert.equal(records[0].csvRow, 2);
assert.equal(matcher.normalizeDate("Oct 13, 2026"), "2026-10-13");
assert.equal(matcher.normalizeDate("10/13/2026"), "2026-10-13");
assert.equal(matcher.amountCents("($1,234.56)"), -123456);
assert.equal(matcher.normalizeText("Visa® 1234"), "visa 1234");

const results = matcher.classifyMatches([
  { date: "10/13/2026", account: "Card 1234", payee: "Google", amount: "-19.99" }
], [
  { rowKey: "one", date: "Oct 13, 2026", account: "Card 1234", payee: "Google", amount: "$19.99" }
]);
assert.equal(results[0].status, "exact");

console.log("matcher-core tests passed");
