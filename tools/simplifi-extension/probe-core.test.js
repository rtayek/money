const assert = require("node:assert/strict");
const probe = require("./probe-core.js");

assert.equal(probe.sanitizeText("Amazon $19.99 on 10/12/2026"), "x <money> x <date>");
assert.equal(probe.sanitizeText("Search transactions by payee"), "search transactions x payee");
assert.equal(probe.sanitizeText("ray@example.com account 12345678"), "<email> account <number>");
assert.deepEqual(probe.shortClasses("one two three"), ["one", "two", "three"]);

console.log("probe-core tests passed");
