(function installSimplifiProbe(root) {
  const knownWords = new Set([
    "account", "accounts", "amount", "apply", "cancel", "category",
    "clear", "close", "date", "edit", "filter", "find", "hide",
    "memo", "note", "notes", "payee", "reviewed", "save", "search",
    "select", "show", "sort", "tag", "tags", "transaction", "transactions"
  ]);

  function sanitizeText(value) {
    if (!value) return "";
    const protectedValues = [];
    const protect = label => {
      const index = protectedValues.push(`<${label}>`) - 1;
      return `§${index}§`;
    };
    return String(value)
      .replace(/\s+/g, " ")
      .trim()
      .slice(0, 300)
      .replace(/[\w.+-]+@[\w.-]+\.[a-z]{2,}/gi, () => protect("email"))
      .replace(/(?:\$\s*)?-?\d{1,3}(?:,\d{3})*(?:\.\d{2})/g, () => protect("money"))
      .replace(/\b(?:\d{1,2}[/-]){2}\d{2,4}\b/g, () => protect("date"))
      .replace(/\b\d{4}-\d{1,2}-\d{1,2}\b/g, () => protect("date"))
      .replace(/\b\d{4,}\b/g, () => protect("number"))
      .replace(/[a-z][a-z'-]*/gi, word => knownWords.has(word.toLowerCase()) ? word.toLowerCase() : "x")
      .replace(/§(\d+)§/g, (match, index) => protectedValues[Number(index)] || match);
  }

  function shortClasses(value) {
    return String(value || "")
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 8);
  }

  function describeElement(element) {
    return {
      tag: element.tagName.toLowerCase(),
      role: element.getAttribute("role") || "",
      type: element.getAttribute("type") || "",
      ariaLabel: sanitizeText(element.getAttribute("aria-label")),
      text: sanitizeText(element.innerText || element.value || ""),
      classes: shortClasses(element.className),
      dataAttributes: Array.from(element.attributes || [])
        .map(attribute => attribute.name)
        .filter(name => name.startsWith("data-"))
        .slice(0, 12)
    };
  }

  function safeFieldName(value) {
    if (!value) return "";
    return /^[a-z][a-z0-9-]{0,60}$/i.test(value) ? value : "<field>";
  }

  function describeCell(element) {
    return {
      tag: element.tagName.toLowerCase(),
      role: element.getAttribute("role") || "",
      field: safeFieldName(element.getAttribute("data-field")),
      columnIndex: element.getAttribute("aria-colindex") || "",
      ariaLabel: sanitizeText(element.getAttribute("aria-label")),
      text: sanitizeText(element.innerText || element.value || ""),
      classes: shortClasses(element.className)
    };
  }

  const api = { describeCell, describeElement, safeFieldName, sanitizeText, shortClasses };
  root.simplifiProbe = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(globalThis);
