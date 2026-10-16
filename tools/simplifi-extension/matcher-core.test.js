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
  { rowKey: "one", date: "Oct 13, 2026", account: "Card 1234", payee: "Google", amount: "-$19.99" }
]);
assert.equal(results[0].status, "exact");

const details = matcher.unmatchedDetails([
  ...results,
  {
    status: "missing",
    target: { csvRow: 12, date: "10/12/2026", payee: "Google", amount: "$3.99" },
    rows: []
  }
], [
  { rowKey: "one", date: "Oct 13, 2026", payee: "Google", amount: "$19.99" },
  { rowKey: "two", date: "Oct 12, 2026", payee: "Google", amount: "$3.99" }
]);
assert.deepEqual(details.missingTargets.map(target => target.csvRow), [12]);
assert.deepEqual(details.unmatchedRows.map(row => row.rowKey), ["two"]);

const report = matcher.sweepReport(127, {
  scannedRows: 128,
  scrollSteps: 20,
  truncated: false,
  counts: { exact: 126, probable: 0, ambiguous: 0, missing: 1 },
  missingTargets: [{ csvRow: 12 }],
  unmatchedRows: [{ amount: "$3.99" }, { amount: "$5.99" }]
});
assert.deepEqual(report.missingCsvTargets, [{ csvRow: 12 }]);
assert.deepEqual(report.unmatchedSimplifiRows, [{ amount: "$3.99" }, { amount: "$5.99" }]);

assert.deepEqual(matcher.mergeNote("", "recommended category: Entertainment"), {
  value: "recommended category: Entertainment",
  changed: true
});
assert.deepEqual(matcher.mergeNote("original note", "original note\nrecommended category: Entertainment"), {
  value: "original note | recommended category: Entertainment",
  changed: true
});
assert.deepEqual(matcher.mergeNote("original note", "recommended category: Entertainment"), {
  value: "original note | recommended category: Entertainment",
  changed: true
});
assert.equal(matcher.mergeNote("recommended category: Entertainment", "recommended category: Entertainment").changed, false);

const candidates = matcher.exactMatchCandidates([
  { csvRow: 2, date: "10/13/2026", account: "Card 1234", payee: "Google", amount: "19.99" },
  { csvRow: 3, date: "10/12/2026", account: "Card 1234", payee: "Google", amount: "1.99" }
], [
  { rowKey: "one", date: "Oct 13, 2026", account: "Card 1234", payee: "Google", amount: "+$19.99" },
  { rowKey: "two", date: "Oct 12, 2026", account: "Card 1234", payee: "Google", amount: "+$1.99" }
], 1);
assert.deepEqual(candidates.map(candidate => candidate.target.csvRow), [2]);



const charge = { csvRow: 20, date: "10/13/2026", account: "Card", payee: "Google", amount: "-19.99" };
const signedRow = { rowKey: "signed", date: "10/13/2026", account: "Card", payee: "Google", amount: "-$19.99" };
assert.equal(matcher.classifyMatches([charge], [signedRow])[0].status, "exact");
assert.equal(matcher.classifyMatches([charge], [{ ...signedRow, amount: "+$19.99" }])[0].status, "missing");
assert.equal(matcher.classifyMatches([charge], [{ ...signedRow, amount: "$19.99" }])[0].status, "probable");
assert.equal(matcher.exactMatchCandidates([charge], [{ ...signedRow, amount: "$19.99" }], 5).length, 0);
assert.equal(matcher.classifyMatches([charge, { ...charge, csvRow: 21 }], [signedRow])[0].status, "ambiguous");
assert.equal(matcher.classifyMatches([charge], [signedRow, { ...signedRow, rowKey: "duplicate" }])[0].status, "ambiguous");
assert.equal(matcher.classifyMatches([{ ...charge, amount: "19.99" }], [{ ...signedRow, amount: "+$19.99" }])[0].status, "exact");
for (const amount of ["", " ", "pending", "$", "1.2.3", "--19.99"]) {
  assert.equal(matcher.amountCents(amount), null);
}
assert.equal(matcher.amountCents("−$19.99"), -1999);
for (const separator of ["\n", "\r\n", "\r", "\u2028", "\u2029"]) {
  const annotation = "original note" + separator + "recommended category: Entertainment";
  const merged = matcher.mergeNote("original note", annotation);
  assert.equal(merged.value, "original note | recommended category: Entertainment");
  assert.equal(matcher.mergeNote(merged.value, annotation).changed, false);
  assert.equal(/[\r\n\u2028\u2029]/.test(merged.value), false);
}
assert.deepEqual(matcher.mergeNote("old\nnote", "new\r\nnote"), {
  value: "old | note | new | note", changed: true
});

console.log("matcher-core tests passed");
