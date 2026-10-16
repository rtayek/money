(function installSimplifiMatcher(root) {
  function parseCsv(text) {
    const rows = [];
    let row = [];
    let field = "";
    let quoted = false;
    for (let index = 0; index < text.length; index++) {
      const character = text[index];
      if (quoted) {
        if (character === '"' && text[index + 1] === '"') {
          field += '"';
          index++;
        } else if (character === '"') {
          quoted = false;
        } else {
          field += character;
        }
      } else if (character === '"') {
        quoted = true;
      } else if (character === ",") {
        row.push(field);
        field = "";
      } else if (character === "\n") {
        row.push(field.replace(/\r$/, ""));
        rows.push(row);
        row = [];
        field = "";
      } else {
        field += character;
      }
    }
    if (field || row.length) {
      row.push(field.replace(/\r$/, ""));
      rows.push(row);
    }
    return rows;
  }

  function recordsFromCsv(text) {
    const rows = parseCsv(text);
    if (!rows.length) return [];
    const headers = rows[0].map((header, index) =>
      (index === 0 ? header.replace(/^\uFEFF/, "") : header).trim());
    return rows.slice(1)
      .filter(row => row.some(value => value.trim()))
      .map((row, index) => Object.fromEntries(headers.map((header, column) =>
        [header, row[column] || ""])))
      .map((record, index) => ({ ...record, csvRow: index + 2 }));
  }

  function normalizeText(value) {
    return String(value || "")
      .normalize("NFKD")
      .replace(/[®™]/g, "")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, " ")
      .trim()
      .replace(/\s+/g, " ");
  }

  function normalizeDate(value) {
    const text = String(value || "").trim();
    let match = text.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
    if (match) return isoDate(match[1], match[2], match[3]);
    match = text.match(/^(\d{1,2})[/-](\d{1,2})[/-](\d{2}|\d{4})$/);
    if (match) {
      const year = match[3].length === 2 ? Number(match[3]) + 2000 : match[3];
      return isoDate(year, match[1], match[2]);
    }
    match = text.match(/^([a-z]{3,9})\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})$/i);
    if (!match) return "";
    const month = months[match[1].slice(0, 3).toLowerCase()];
    return month ? isoDate(match[3], month, match[2]) : "";
  }

  function isoDate(year, month, day) {
    return `${String(year).padStart(4, "0")}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
  }

  function amountCents(value) {
    const original = String(value ?? "").trim().replace(/\u2212/g, "-");
    if (!/^(?:[+-]?\s*\$?\s*\d[\d,]*(?:\.\d{1,2})?|\$\s*[+-]?\s*\d[\d,]*(?:\.\d{1,2})?|\(\s*\$?\s*\d[\d,]*(?:\.\d{1,2})?\s*\))$/.test(original)) return null;
    const negative = /^\(.*\)$/.test(original) || original.includes("-");
    const number = Number(original.replace(/[^0-9.]/g, ""));
    if (!Number.isFinite(number)) return null;
    return Math.round(number * 100) * (negative ? -1 : 1);
  }

  function pageAmountCents(value) {
    const cents = amountCents(value);
    if (cents === null) return null;
    return String(value).includes("+") ? cents : -Math.abs(cents);
  }

  function prepareTransaction(transaction, fromPage = false) {
    return {
      ...transaction,
      dateKey: normalizeDate(transaction.date),
      accountKey: normalizeText(transaction.account),
      payeeKey: normalizeText(transaction.payee),
      amountKey: fromPage ? pageAmountCents(transaction.amount) : amountCents(transaction.amount)
    };
  }

  function sameCore(left, right) {
    return left.dateKey && left.dateKey === right.dateKey &&
      left.payeeKey && left.payeeKey === right.payeeKey &&
      left.amountKey !== null && right.amountKey !== null &&
      left.amountKey === right.amountKey;
  }

  function classifyMatches(targets, visibleRows) {
    const preparedRows = visibleRows.map(row => prepareTransaction(row, true));
    const results = targets.map(target => {
      const preparedTarget = prepareTransaction(target);
      const core = preparedRows.filter(row => sameCore(preparedTarget, row));
      const exactAccount = core.filter(row => preparedTarget.accountKey &&
        preparedTarget.accountKey === row.accountKey);
      if (exactAccount.length === 1) return { status: "exact", target, rows: exactAccount };
      if (exactAccount.length > 1) return { status: "ambiguous", target, rows: exactAccount };
      if (core.length === 1) return { status: "probable", target, rows: core };
      if (core.length > 1) return { status: "ambiguous", target, rows: core };
      return { status: "missing", target, rows: [] };
    });
    const claims = new Map();
    for (const result of results.filter(result => result.rows.length === 1)) {
      const rowKey = result.rows[0].rowKey;
      claims.set(rowKey, (claims.get(rowKey) || 0) + 1);
    }
    for (const result of results) {
      if (result.rows.length === 1 && claims.get(result.rows[0].rowKey) > 1) result.status = "ambiguous";
    }
    return results;
  }

  function unmatchedDetails(results, rows) {
    const matchedRowKeys = new Set(results.flatMap(result =>
      result.rows.map(row => row.rowKey).filter(Boolean)));
    return {
      missingTargets: results
        .filter(result => result.status === "missing")
        .map(result => result.target),
      unmatchedRows: rows.filter(row => row.rowKey && !matchedRowKeys.has(row.rowKey))
    };
  }

  function sweepReport(targetCount, response) {
    return {
      csvTargets: targetCount,
      scannedTransactionRows: response.scannedRows,
      scrollSteps: response.scrollSteps,
      sweepComplete: !response.truncated,
      ...response.counts,
      missingCsvTargets: response.missingTargets,
      unmatchedSimplifiRows: response.unmatchedRows,
      legend: "green=exact direction-aware match, yellow=probable account mismatch, red=ambiguous"
    };
  }

  function mergeNote(existing, annotated) {
    const current = String(existing || "");
    const singleLine = value => String(value || "").replace(/\s*[\r\n\u2028\u2029]+\s*/g, " | ").trim();
    const desired = singleLine(annotated);
    const trimmedCurrent = singleLine(current);
    if (!desired || trimmedCurrent.includes(desired)) {
      return { value: trimmedCurrent, changed: trimmedCurrent !== current };
    }
    if (trimmedCurrent && desired.startsWith(trimmedCurrent)) {
      return { value: desired, changed: desired !== current };
    }
    return {
      value: trimmedCurrent ? `${trimmedCurrent} | ${desired}` : desired,
      changed: true
    };
  }

  function exactMatchCandidates(targets, rows, limit) {
    return classifyMatches(targets, rows)
      .filter(result => result.status === "exact" && result.rows.length === 1)
      .slice(0, limit);
  }

  const months = {
    jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6,
    jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12
  };
  const api = {
    amountCents,
    classifyMatches,
    exactMatchCandidates,
    mergeNote,
    normalizeDate,
    normalizeText,
    parseCsv,
    recordsFromCsv,
    sweepReport,
    unmatchedDetails
  };
  root.simplifiMatcher = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(globalThis);
