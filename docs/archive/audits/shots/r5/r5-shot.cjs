const { chromium } = require("playwright-core");
const fs = require("fs");
const path = require("path");
const OUT = __dirname;
const EDGE = "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe";
const BASE = "http://localhost:5173";
const USER = "owner";
const PASS = "12345678";
async function settle(page) {
  await page.waitForSelector(".el-loading-mask", { state: "hidden", timeout: 30000 }).catch(function () {});
  await page.waitForSelector(".el-message", { state: "hidden", timeout: 20000 }).catch(function () {});
  await page.waitForTimeout(800);
}
async function audit(page) {
  return await page.evaluate(() => {
    const h1 = Array.from(document.querySelectorAll("h1")).map(function (e) { return e.innerText.trim(); });
    const text = document.body.innerText || "";
    const m = text.match(/\b[A-Z]{2,}(?:_[A-Z0-9]+)+\b/g) || [];
    return { h1count: h1.length, h1text: h1, rawEnums: Array.from(new Set(m)) };
  });
}
async function snap(page, name, report) {
  await page.waitForSelector("h1", { timeout: 20000 }).catch(function () {});
  await settle(page);
  await page.screenshot({ path: path.join(OUT, name + ".png") });
  const a = await audit(page);
  a.page = name;
  report.push(a);
  console.log("SHOT " + name + " h1=" + a.h1count + " raw=" + JSON.stringify(a.rawEnums));
}
(async function () {
  const report = [];
  const consoleErrors = [];
  const browser = await chromium.launch({ executablePath: EDGE, headless: true, args: ["--no-sandbox", "--disable-dev-shm-usage"] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  page.on("console", function (m) { if (m.type() === "error") consoleErrors.push(m.text()); });
  page.on("pageerror", function (e) { consoleErrors.push(String(e)); });
  await page.goto(BASE + "/login", { waitUntil: "domcontentloaded" });
  await page.waitForSelector("input[autocomplete=username]", { timeout: 20000 });
  await page.fill("input[autocomplete=username]", USER);
  await page.fill("input[autocomplete=current-password]", PASS);
  await page.click(".submit-button");
  await page.waitForURL("**/home", { timeout: 20000 });
  await settle(page);
  await snap(page, "desktop-home", report);
  await page.goto(BASE + "/projects", { waitUntil: "domcontentloaded" });
  await page.waitForSelector("a[href*=\"/dashboard\"]", { timeout: 30000 }).catch(function () {});
  await page.waitForTimeout(1200);
  const pid = await page.evaluate(function () {
    const a = document.querySelector("a[href*=\"/dashboard\"]");
    if (!a) return null;
    const m = a.getAttribute("href").match(/\/projects\/([^\/]+)\/dashboard/);
    return m ? m[1] : null;
  });
  console.log("PROJECT " + pid);
  await snap(page, "desktop-projects", report);
  if (!pid) throw new Error("no demo project found");
  const routes = [["desktop-overview", "/dashboard"], ["desktop-tasks", "/board"], ["desktop-planning", "/ai-planning"], ["desktop-agent", "/agent"], ["desktop-riskanalysis", "/risk-analysis"]];
  for (const r of routes) {
    await page.goto(BASE + "/projects/" + pid + r[1], { waitUntil: "domcontentloaded" });
    await snap(page, r[0], report);
  }
  await page.goto(BASE + "/settings/ai", { waitUntil: "domcontentloaded" });
  await page.waitForSelector(".provider-tile", { timeout: 30000 }).catch(function () {});
  await snap(page, "desktop-ai-settings", report);
  await page.goto(BASE + "/home", { waitUntil: "domcontentloaded" });
  await page.waitForSelector("h1", { timeout: 20000 }).catch(function () {});
  await settle(page);
  await page.screenshot({ path: path.join(OUT, "desktop-sidebar-expanded.png") });
  report.push({ page: "desktop-sidebar-expanded" });
  console.log("SHOT desktop-sidebar-expanded");
  await page.click("button[aria-label=\"收起侧栏\"]");
  await page.waitForTimeout(800);
  await page.screenshot({ path: path.join(OUT, "desktop-sidebar-collapsed.png") });
  const col = await page.evaluate(function () {
    const sb = document.querySelector(".app-sidebar");
    return sb ? Math.round(sb.getBoundingClientRect().width) : -1;
  });
  report.push({ page: "desktop-sidebar-collapsed", sidebarWidth: col });
  console.log("SHOT desktop-sidebar-collapsed width=" + col);
  const mctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const m = await mctx.newPage();
  m.on("console", function (e) { if (e.type() === "error") consoleErrors.push("[m] " + e.text()); });
  m.on("pageerror", function (e) { consoleErrors.push("[m] " + String(e)); });
  await m.goto(BASE + "/login", { waitUntil: "domcontentloaded" });
  await m.waitForSelector("input[autocomplete=username]", { timeout: 20000 });
  await m.fill("input[autocomplete=username]", USER);
  await m.fill("input[autocomplete=current-password]", PASS);
  await m.click(".submit-button");
  await m.waitForURL("**/home", { timeout: 20000 });
  await settle(m);
  await snap(m, "mobile-home", report);
  await m.screenshot({ path: path.join(OUT, "mobile-nav-closed.png") });
  report.push({ page: "mobile-nav-closed" });
  console.log("SHOT mobile-nav-closed");
  await m.click(".hamburger");
  await m.waitForTimeout(800);
  await m.screenshot({ path: path.join(OUT, "mobile-nav-open.png") });
  const mw = await m.evaluate(function () {
    const sb = document.querySelector(".app-sidebar");
    return sb ? Math.round(sb.getBoundingClientRect().width) : -1;
  });
  report.push({ page: "mobile-nav-open", sidebarWidth: mw });
  console.log("SHOT mobile-nav-open width=" + mw);
  await m.goto(BASE + "/projects/" + pid + "/agent", { waitUntil: "domcontentloaded" });
  await snap(m, "mobile-agent", report);
  await m.goto(BASE + "/settings/ai", { waitUntil: "domcontentloaded" });
  await m.waitForSelector(".provider-tile", { timeout: 30000 }).catch(function () {});
  await snap(m, "mobile-ai-settings", report);
  report.push({ consoleErrors: consoleErrors });
  fs.writeFileSync(path.join(OUT, "audit-report.json"), JSON.stringify(report, null, 1));
  await browser.close();
  console.log("DONE");
})().catch(function (e) { console.error("FATAL " + (e && e.stack || e)); process.exit(1); });
