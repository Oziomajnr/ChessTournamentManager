"use strict";

const feed = document.querySelector('meta[name="delivery-feed"]').content;
const select = document.querySelector("#issue");
const graph = document.querySelector("#graph");
const detail = document.querySelector("#detail");
const notice = document.querySelector("#notice");
const sync = document.querySelector("#sync");
const refreshButton = document.querySelector("#refresh");
const checkInterval = 30_000;
const timeFormat = new Intl.DateTimeFormat(undefined, { timeStyle: "medium" });
let board;
let lastPayload;
let checking = false;

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function selectedNumber() {
  return new URL(location.href).searchParams.get("issue") || "";
}

function selectionUrl(number) {
  const url = new URL(location.href);
  if (number) url.searchParams.set("issue", number);
  else url.searchParams.delete("issue");
  return url;
}

function evidenceLink(label, href) {
  const url = new URL(href);
  const link = element("a", label);
  if (url.origin === "https://github.com") link.href = url.href;
  return link;
}

function showDetail(issue, number) {
  detail.replaceChildren();
  detail.hidden = !number;
  if (!number) return;
  if (!issue) {
    detail.append(element("p", "Issue #" + number + " is absent from the latest published workflow."));
    return;
  }
  detail.append(element("h2", "#" + issue.number + " · " + issue.title));
  const state = element("p");
  state.append(element("strong", issue.stage), document.createTextNode(" · " + issue.detail));
  const links = element("div", undefined, "references");
  links.append(evidenceLink("Open issue", issue.url));
  for (const link of issue.links) links.append(evidenceLink(link.label, link.url));
  detail.append(state, links);
}

function issueCard(issue, step) {
  const card = element("a", undefined, "issue-card");
  card.href = selectionUrl(issue.number);
  card.dataset.issue = issue.number;
  card.append(element("strong", "#" + issue.number), document.createTextNode(" " + issue.title));
  if (step.label !== issue.stage) card.append(element("small", issue.stage));
  return card;
}

function paint() {
  if (!board) return;
  const number = selectedNumber();
  const selected = board.issues.find(i => String(i.number) === number);
  select.replaceChildren(new Option("All issues", ""));
  for (const i of board.issues) select.add(new Option("#" + i.number + " · " + i.title, i.number));
  if (number && !selected) select.add(new Option("#" + number + " · Unavailable", number));
  select.value = number;
  select.disabled = false;
  showDetail(selected, number);
  const visible = number ? (selected ? [selected] : []) : board.issues;
  const lanes = document.createDocumentFragment();
  for (const lane of board.lanes) {
    const side = lane.id === "exceptions";
    const steps = element("ol");
    for (const step of lane.steps) {
      const issues = visible.filter(i => i.step === step.id);
      if (side && !issues.length) continue;
      const row = element("li", undefined, issues.length ? "step occupied" : "step");
      row.dataset.step = step.id;
      row.append(element("h3", step.label));
      for (const issue of issues) row.append(issueCard(issue, step));
      steps.append(row);
    }
    if (!steps.childElementCount) continue;
    const section = element("section", undefined, side ? "lane other" : "lane");
    section.append(element("h2", lane.label), steps);
    lanes.append(section);
  }
  const focused = graph.contains(document.activeElement) && document.activeElement.dataset.issue;
  graph.replaceChildren(lanes);
  if (focused) graph.querySelector('[data-issue="' + CSS.escape(focused) + '"]')?.focus({preventScroll: true});
}

function follow(value) {
  if (value !== selectedNumber()) history.pushState(null, "", selectionUrl(value));
  paint();
}

graph.addEventListener("click", event => {
  const card = event.target.closest(".issue-card");
  if (!card || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
  event.preventDefault();
  follow(card.dataset.issue);
});
select.addEventListener("change", () => follow(select.value));
window.addEventListener("popstate", paint);

async function refresh() {
  if (checking) return;
  checking = true;
  refreshButton.disabled = true;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 12_000);
  try {
    const url = new URL(feed, location.href);
    url.searchParams.set("v", Math.floor(Date.now() / checkInterval));
    const response = await fetch(url, { cache: "no-store", credentials: "omit", signal: controller.signal });
    if (!response.ok) throw new Error("Status unavailable");
    const payload = await response.text();
    if (payload !== lastPayload) {
      const next = JSON.parse(payload);
      if (next.version !== 1 || !Array.isArray(next.lanes) || !Array.isArray(next.issues)) throw new Error("Unknown status format");
      const previous = board?.issues.find(i => String(i.number) === selectedNumber());
      board = next;
      paint();
      lastPayload = payload;
      const current = board.issues.find(i => String(i.number) === selectedNumber());
      if (previous && current && previous.stage !== current.stage) {
        document.querySelector("#announcement").textContent = "#" + current.number + " moved from " + previous.stage + " to " + current.stage + ".";
      }
    }
    notice.hidden = true;
    sync.textContent = "Checked " + timeFormat.format(new Date());
  } catch {
    notice.textContent = board ? "Showing the last received status. The viewer will retry automatically."
      : "The workflow could not be loaded. The viewer will retry automatically.";
    notice.hidden = false;
    if (!board) sync.textContent = "Awaiting status";
  } finally {
    clearTimeout(timeout);
    checking = false;
    refreshButton.disabled = false;
  }
}

refreshButton.addEventListener("click", refresh);
document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); });
setInterval(() => { if (!document.hidden) refresh(); }, checkInterval);
refresh();
