/* Optional recording tool: npm install playwright, or set PLAYWRIGHT_MODULE. */
"use strict";
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("fs");
const path = require("path");
const options = Object.fromEntries(process.argv.slice(2).map((value) => {
  const separator = value.indexOf("=");
  if (!value.startsWith("--") || separator < 0) throw new Error("Use --name=value arguments");
  return [value.slice(2, separator), value.slice(separator + 1)];
}));
const demoUrl = options["demo-url"] || "http://127.0.0.1:18086";
const modelUrl = options["model-url"] || "http://127.0.0.1:18080";
const output = path.resolve(options.output || "target/demo-video");
const modelFile = options["model-run"];
const clips = [];
const viewport = { width: 1280, height: 800 };
let activeBrowser;

async function pageFor(browser) {
  const context = await browser.newContext({ viewport, recordVideo: { dir: output, size: viewport } });
  await context.addInitScript(() => {
    document.addEventListener("DOMContentLoaded", () => {
      const uuid = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
      const redact = () => {
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        let node;
        while ((node = walker.nextNode())) {
          if (uuid.test(node.nodeValue)) node.nodeValue = node.nodeValue.replace(new RegExp(uuid.source, "gi"), "[已脱敏]");
        }
      };
      new MutationObserver(redact).observe(document.body, { childList: true, subtree: true, characterData: true });
      redact();
    });
  });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.recordingErrors = errors;
  return { context, page };
}

async function caption(page, text, milliseconds = 2500) {
  await page.evaluate((text) => {
    let banner = document.getElementById("recording-caption");
    if (!banner) {
      banner = document.createElement("div");
      banner.id = "recording-caption";
      Object.assign(banner.style, {
        position: "fixed", bottom: "12px", left: "250px", right: "20px", zIndex: "10000",
        padding: "14px 20px", border: "1px solid #246bd6", borderLeft: "5px solid #246bd6",
        borderRadius: "8px", background: "rgba(255,255,255,.97)", color: "#193149",
        font: 'bold 17px/1.5 "Microsoft YaHei", sans-serif', pointerEvents: "none",
        boxShadow: "0 3px 18px #19314920",
      });
      document.body.append(banner);
    }
    banner.textContent = text;
  }, text);
  await page.waitForTimeout(milliseconds);
}

async function investigate(page, scenario, question) {
  await page.locator(`[data-lab-scenario="${scenario}"]`).click();
  await page.locator("#live-lab-status").getByText("已处理 5 个订单请求", { exact: false }).waitFor();
  await page.locator("#question").fill(question);
  await caption(page, scenario === "NORMAL" ? "生成 5 次正常请求，查看当前窗口观测" : "库存响应变慢，订单侧真实 HTTP 请求超时", 2000);
  await page.locator("#submit-button").click();
  await page.locator("#run-status").getByText("已完成", { exact: true }).waitFor();
  await page.locator("#metrics").scrollIntoViewIfNeeded();
}

async function finish(context, page, name) {
  if (page.recordingErrors.length) throw new Error(page.recordingErrors.join("\n"));
  const video = page.video();
  await context.close();
  const destination = path.join(output, name + ".webm");
  await video.saveAs(destination);
  clips.push({ name, file: name + ".webm" });
}

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch();
  activeBrowser = browser;
  const { context, page } = await pageFor(browser);
  const config = await (await page.request.get(demoUrl + "/api/config")).json();
  if (config.mode !== "DEMO" || config.observationSource !== "LIVE") throw new Error("Recording requires isolated DEMO + LIVE; no paid model calls");
  await page.goto(demoUrl);
  await page.locator("#mode-label").getByText("实测演示", { exact: true }).waitFor();
  await caption(page, "Agent Triage · 实际 HTTP 请求演示（固定规则模式）", 3000);
  await investigate(page, "NORMAL", "当前订单是否出现库存调用超时？");
  await caption(page, "正常对照：结论只描述本窗口，不宣布整个服务健康", 4000);
  await page.locator("[data-lab-scenario='DOWNSTREAM_TIMEOUT']").scrollIntoViewIfNeeded();
  await investigate(page, "DOWNSTREAM_TIMEOUT", "订单查询为什么变慢？请结合当前请求给出证据。");
  await caption(page, "指标与错误事件来自本地订单、库存两个独立 JVM", 4000);
  await page.locator("#overview-panel a[href^='#evidence-']").first().click();
  await caption(page, "点击引用，直接查看对应窗口指标与排障规则", 4500);
  await page.locator("[id^='evidence-LOGS'] > summary").click();
  await page.locator("[id^='evidence-LOGS']").scrollIntoViewIfNeeded();
  await caption(page, "错误事件可核对；录像中完整标识符已脱敏", 4500);
  await page.locator("[data-tab='events']").click();
  await page.locator("#events").scrollIntoViewIfNeeded();
  await caption(page, "执行记录保留工具顺序和结果；Agent 工具只读", 4000);
  await page.locator("#history .history-item").last().click();
  await page.locator("#metrics").scrollIntoViewIfNeeded();
  await caption(page, "从历史重新打开正常对照，观察与引用仍可查看", 3000);
  await finish(context, page, "live-workflow");

  if (modelFile) {
    const run = JSON.parse(fs.readFileSync(modelFile, "utf8"));
    if (run.mode !== "MODEL" || run.status !== "SUCCEEDED" || !run.events.some((event) => event.type === "CONCLUSION_RENDERED")) throw new Error("Expected an existing validated model run");
    const recorded = await pageFor(browser);
    await recorded.page.route("**/api/runs?limit=20", async (route) => {
      const response = await route.fetch();
      const history = await response.json();
      await route.fulfill({ response, json: history.filter((item) => item.id === run.id) });
    });
    await recorded.page.goto(modelUrl);
    await recorded.page.evaluate((id) => selectRun(id), run.id);
    await recorded.page.locator("#metrics").scrollIntoViewIfNeeded();
    await caption(recorded.page, "真实百炼评测结果回放 · 已保存历史，不重新请求模型", 4500);
    await recorded.page.getByText("建议检查", { exact: true }).scrollIntoViewIfNeeded();
    await caption(recorded.page, "模型选工具与证据，应用生成结论并排序展示两项建议", 6000);
    await recorded.page.locator("[data-tab='events']").click();
    await recorded.page.locator("#events").scrollIntoViewIfNeeded();
    await caption(recorded.page, "展示调用事件和证据，不展示模型内部思考或 API Key", 4000);
    await finish(recorded.context, recorded.page, "model-history");
  }
  await browser.close();
  fs.writeFileSync(path.join(output, "clips.json"), JSON.stringify({ clips, viewport, silent: true, identifiersRedacted: true }, null, 2) + "\n");
  console.log("Recorded " + clips.length + " clips in " + output);
})().catch(async (error) => {
  console.error(error.message);
  if (activeBrowser) await activeBrowser.close();
  process.exitCode = 1;
});
