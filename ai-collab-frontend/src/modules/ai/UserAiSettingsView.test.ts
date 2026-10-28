// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils"
import UserAiSettingsView from "./UserAiSettingsView.vue"
import { beforeEach, describe, expect, it, vi } from "vitest"
import type { UserAiProvider } from "./user-ai-api"

const authState = vi.hoisted(() => ({
  currentUser: { id: "u-1", systemAdmin: false } as { id: string; systemAdmin: boolean } | null,
  listeners: [] as Array<() => void>,
}))
function switchUser(user: { id: string; systemAdmin: boolean } | null) {
  authState.currentUser = user;
  authState.listeners.forEach((cb) => cb());
}

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  listPurposes: vi.fn(),
  testCapability: vi.fn(),
  detectAndConfigure: vi.fn(),
  probeResults: vi.fn(),
  providerModels: vi.fn(),
  profiles: vi.fn(),
  presets: vi.fn(),
  models: vi.fn(),
  save: vi.fn(),
  disconnect: vi.fn(),
  confirm: vi.fn(),
  success: vi.fn(),
}));

vi.mock("../../stores/auth-store", () => ({
  useAuthStore: () => ({
    get currentUser() {
      return authState.currentUser;
    },
    $subscribe: (cb: () => void) => {
      authState.listeners.push(cb);
    },
  }),
}));
vi.mock("./user-ai-api", () => ({
  userAiApi: {
    list: mocks.list, listPurposes: mocks.listPurposes,
    testCapability: mocks.testCapability, detectAndConfigure: mocks.detectAndConfigure,
    probeResults: mocks.probeResults,
    models: mocks.providerModels, profiles: mocks.profiles,
    create: vi.fn(), update: vi.fn(), remove: vi.fn(), setDefault: vi.fn(), test: vi.fn(),
    assignPurpose: vi.fn(), unassignPurpose: vi.fn(), discoverUnsavedModels: vi.fn(),
  },
}));
vi.mock("./ai-preset-api", () => ({
  aiPresetApi: { presets: mocks.presets, models: mocks.models, save: mocks.save, disconnect: mocks.disconnect },
}));
vi.mock("element-plus", () => ({
  ElMessage: { success: mocks.success, error: vi.fn(), warning: vi.fn() },
  ElMessageBox: { confirm: mocks.confirm },
}));

const response = <T,>(data: T): { data: T } => ({ data });
const zen = (over = {}) => ({
  code: "OPENCODE_ZEN_FREE", displayName: "OpenCode Zen",
  connected: true, modelName: "mimo-v2.5-free", enabled: true, isDefault: false, hasApiKey: true,
  ...over,
});
const kilo = (over = {}) => ({
  code: "KILO_GATEWAY_FREE", displayName: "Kilo Gateway Free",
  connected: false, modelName: null, enabled: true, isDefault: false, hasApiKey: false,
  ...over,
});
const customProvider = (over: Partial<UserAiProvider> = {}): UserAiProvider => ({
  id: "c-1", name: "我的网关", presetCode: null, providerType: "OPENAI_COMPATIBLE",
  baseUrl: "https://api.example.com", apiPath: "/v1/chat/completions", hasApiKey: true,
  modelName: "my-model", enabled: true, isDefault: true, temperature: 0.2, maxOutputTokens: 1200,
  capabilities: ["CHAT", "STREAMING"], updatedAt: "2026-10-20T00:00:00Z", ...over,
});

const stubs = {
  PageHeader: { template: "<header />" },
  EmptyState: { template: "<div />" },
  ElButton: { inheritAttrs: false, template: '<button v-bind="$attrs"><slot /></button>' },
  ElCard: { template: '<section><slot name="header" /><slot /></section>' },
  ElInput: { template: "<textarea />" },
  "el-select": { inheritAttrs: false, template: '<div v-bind="$attrs"><slot /></div>' },
  "el-option": { props: ["label", "value", "disabled"], template: '<span>{{ label }}</span>' },
  ElSkeleton: { template: "<div />" },
  "el-checkbox": { inheritAttrs: false, template: '<label v-bind="$attrs"><slot /></label>' },
  "el-checkbox-group": { template: "<div><slot /></div>" },
  ElTag: { props: ["type", "size"], template: '<span class="el-tag-stub"><slot /></span>' },
  ElAlert: { props: ["title", "description", "type"], template: '<div>{{ title }} {{ description }}<slot /></div>' },
  RouterLink: { props: ["to"], template: '<a :href="typeof to === \'string\' ? to : to?.path"><slot /></a>' },
  ProviderFormDialog: { template: "<div />" },
};

function mountView() {
  return mount(UserAiSettingsView, { global: { directives: { loading: () => undefined }, stubs } });
}

function buttonByText(wrapper: ReturnType<typeof mountView>, text: string) {
  return wrapper.findAll("button").find((b) => b.text() === text);
}

beforeEach(() => {
  vi.resetAllMocks();
  authState.currentUser = { id: "u-1", systemAdmin: false };
  authState.listeners = [];
  mocks.list.mockResolvedValue(response([]));
  mocks.listPurposes.mockResolvedValue(response({}));
  mocks.probeResults.mockResolvedValue(response([]));
  mocks.providerModels.mockResolvedValue(response(["my-model"]));
  mocks.profiles.mockRejectedValue(new Error("profiles unavailable"));
  mocks.presets.mockResolvedValueOnce(response([zen(), kilo()]));
  mocks.presets.mockResolvedValue(response([zen({ connected: false, modelName: null, hasApiKey: false, isDefault: false }), kilo()]));
  mocks.models.mockResolvedValue(response(["mimo-v2.5-free"]));
  mocks.testCapability.mockResolvedValue(response({}));
  mocks.detectAndConfigure.mockResolvedValue(response({
    steps: [
      { mode: "CONNECTIVITY", status: "PASS", detail: null, errorCode: null, latencyMs: 30 },
      { mode: "STREAMING", status: "PASS", detail: null, errorCode: null, latencyMs: 40 },
    ],
    writeBack: { applied: true, reason: null, capabilitiesAfter: ["CHAT", "STREAMING"] },
    generationRequests: 2,
  }));
  mocks.disconnect.mockResolvedValue(undefined);
  mocks.confirm.mockResolvedValue(true);
});

describe("UserAiSettingsView connectivity/disconnect", () => {
  it("disconnects Zen with confirm and reloads", async () => {
    const wrapper = mountView();
    await flushPromises();
    const btn = buttonByText(wrapper, "断开连接");
    expect(btn).toBeTruthy();
    await btn?.trigger("click");
    await flushPromises();
    expect(mocks.confirm).toHaveBeenCalledTimes(1);
    expect(mocks.disconnect).toHaveBeenCalledTimes(1);
    expect(mocks.success).toHaveBeenCalledWith("已断开 OpenCode Zen Free 连接");
    wrapper.unmount();
  });

  it("connects and disconnects Kilo Gateway Free without an API Key", async () => {
    mocks.presets.mockReset();
    mocks.presets.mockResolvedValue(response([
      zen({ connected: false, modelName: null, hasApiKey: false, isDefault: false }),
      kilo({ connected: true, modelName: "poolside/laguna-s.1:free", isDefault: true }),
    ]));
    mocks.models.mockResolvedValue(response(["poolside/laguna-s.1:free"]));
    const wrapper = mountView();
    await flushPromises();
    expect(mocks.models).toHaveBeenCalledWith("KILO_GATEWAY_FREE", false);
    expect(wrapper.text()).toContain("Kilo Gateway");
    const disconnectButtons = wrapper.findAll("button").filter((b) => b.text() === "断开连接");
    expect(disconnectButtons.length).toBe(1);
    await disconnectButtons[0]!.trigger("click");
    await flushPromises();
    expect(mocks.disconnect).toHaveBeenCalledWith("KILO_GATEWAY_FREE");
    expect(mocks.success).toHaveBeenCalledWith("已断开 Kilo Gateway Free 连接");
    wrapper.unmount();
  });

  it("shows an error state instead of faking defaults when purposes fail", async () => {
    mocks.listPurposes.mockRejectedValueOnce(new Error("boom"));
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain("用途覆盖加载失败");
    expect(buttonByText(wrapper, "重试")).toBeTruthy();
    mocks.listPurposes.mockResolvedValue(response({ KNOWLEDGE_CHAT: "p-1" }));
    await buttonByText(wrapper, "重试")?.trigger("click");
    await flushPromises();
    expect(wrapper.text()).not.toContain("用途覆盖加载失败");
    wrapper.unmount();
  });
});

describe("UserAiSettingsView state accuracy", () => {
  it("keeps the AI config unknown, not empty, when loading fails", async () => {
    mocks.list.mockRejectedValue(new Error("boom"));
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain("AI 配置加载失败，当前状态未知");
    expect(wrapper.text()).not.toContain("还没有自定义模型");
    wrapper.unmount();
  });

  it("never probes a model without an explicit click, including a refresh", async () => {
    mocks.list.mockRejectedValueOnce(new Error("boom"));
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain("AI 配置加载失败");
    mocks.list.mockResolvedValue(response([customProvider()]));
    await buttonByText(wrapper, "重试")?.trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("默认模型：我的网关 · my-model")
    expect(mocks.testCapability).not.toHaveBeenCalled();
    // 模型目录列举不是真实生成探测
    expect(mocks.models).toHaveBeenCalled();
    wrapper.unmount();
  });

  it("describes the resolved default per purpose with readiness derived from probe evidence", async () => {
    // 未测过任何探针：知识问答显示“未验证”（不是可用，也不是不可用）。
    mocks.list.mockResolvedValue(response([customProvider()]));
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain("默认模型：我的网关 · my-model · 已启用");
    expect(wrapper.text()).toContain("未验证");
    expect(wrapper.text()).not.toContain("知识问答我的网关默认模型：我的网关 · my-model · 已启用 可用");
    wrapper.unmount();
  });

  it("marks a purpose READY only when its required probe passed, not on connectivity alone", async () => {
    mocks.list.mockResolvedValue(response([customProvider()]));
    // 连接通过 + 流式通过 → 知识问答“可用”；规划（依赖 PLANNING_JSON）仍“未验证”。
    mocks.probeResults.mockResolvedValue(response([
      { mode: "CONNECTIVITY", status: "PASS", model: "my-model", detail: null, errorCode: null, latencyMs: 80, testedAt: "2026-10-24T00:00:00Z", stale: false },
      { mode: "STREAMING", status: "PASS", model: "my-model", detail: null, errorCode: null, latencyMs: 220, testedAt: "2026-10-24T00:00:00Z", stale: false },
    ]));
    const wrapper = mountView();
    await flushPromises();
    const knowledgeRow = wrapper.text().match(/知识问答[\s\S]*?(可用|未验证|不可用|未启用所需能力)/)?.[1] ?? "";
    expect(knowledgeRow).toBe("可用");
    const planningRow = wrapper.text().match(/AI 规划[\s\S]*?(可用|未验证|不可用|未启用所需能力)/)?.[1] ?? "";
    expect(planningRow).toBe("未验证");
    wrapper.unmount();
  });

  it("marks readiness stale when the config changed after the probe", async () => {
    mocks.list.mockResolvedValue(response([customProvider()]));
    mocks.probeResults.mockResolvedValue(response([
      { mode: "STREAMING", status: "PASS", model: "my-model", detail: null, errorCode: null, latencyMs: 220, testedAt: "2026-10-23T00:00:00Z", stale: true },
    ]));
    const wrapper = mountView();
    await flushPromises();
    const knowledgeRow = wrapper.text().match(/知识问答[\s\S]*?(可用|未验证|不可用|未启用所需能力)/)?.[1] ?? "";
    expect(knowledgeRow).toBe("未验证");
    expect(wrapper.text()).toContain("已过期（配置已变更）");
    wrapper.unmount();
  });

  it("explains an unresolvable purpose when no default or override exists", async () => {
    mocks.list.mockResolvedValue(response([customProvider({ isDefault: false })]));
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain("没有默认模型，也没有该用途的覆盖，当前不可用");
    wrapper.unmount();
  });

  it("marks a purpose blocked when its override points at a disabled provider", async () => {
    mocks.list.mockResolvedValue(response([
      customProvider({ id: "d-1", name: "默认网关", isDefault: true, enabled: true }),
      customProvider({ id: "o-1", name: "停用覆盖", enabled: false, isDefault: false }),
    ]));
    mocks.listPurposes.mockResolvedValue(response({ AGENT: "o-1" }));
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain("用途覆盖指向已停用的模型“停用覆盖”，该用途当前不可用");
    expect(wrapper.text()).toContain("停用覆盖（已停用）");
    expect(wrapper.text()).not.toContain("o-1");
    wrapper.unmount();
  });

  it("runs the unified capability suite on one explicit click for a custom provider", async () => {
    mocks.list.mockResolvedValue(response([customProvider()]));
    const wrapper = mountView();
    await flushPromises();
    await buttonByText(wrapper, "检测并配置")?.trigger("click");
    await flushPromises();
    // 一次确认只发起一次后端编排调用（串行步骤在后端执行）
    expect(mocks.detectAndConfigure).toHaveBeenCalledTimes(1);
    expect(mocks.detectAndConfigure).toHaveBeenCalledWith("c-1", { planning: false, nativeTools: false });
    expect(mocks.testCapability).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("连接（真实生成）");
    expect(wrapper.text()).toContain("流式输出");
    wrapper.unmount();
  });

  it("offers the unified capability suite for connected presets too", async () => {
    // Zen 已连接：预设卡片渲染同一套检测入口，能力检测走统一编排通道（写回由服务端策略守卫）。
    mocks.list.mockResolvedValue(response([customProvider({
      id: "zen-row", presetCode: "OPENCODE_ZEN_FREE", isDefault: false,
    })]));
    const wrapper = mountView();
    await flushPromises();
    const suiteButtons = wrapper.findAll("button").filter((b) => b.text() === "检测并配置");
    expect(suiteButtons.length).toBeGreaterThanOrEqual(1);
    await suiteButtons[0]!.trigger("click");
    await flushPromises();
    expect(mocks.detectAndConfigure).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it("hides the system Embedding admin entry from members", async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain("个人聊天模型与系统 Embedding 是两套配置");
    expect(wrapper.text()).not.toContain("前往系统 Embedding 管理");
    expect(wrapper.findAll('a[href="/admin/embedding"]')).toHaveLength(0);
    wrapper.unmount();
  });

  it("shows the system Embedding admin entry only to system admins", async () => {
    authState.currentUser = { id: "u-1", systemAdmin: true };
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain("前往系统 Embedding 管理");
    expect(wrapper.findAll('a[href="/admin/embedding"]')).toHaveLength(1);
    wrapper.unmount();
  });

  it("drops the previous user's configuration on account switch", async () => {
    mocks.list.mockResolvedValue(response([customProvider()]));
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain("我的网关");

    mocks.list.mockResolvedValue(response([]));
    mocks.listPurposes.mockResolvedValue(response({}));
    switchUser({ id: "u-2", systemAdmin: false });
    await flushPromises();

    expect(wrapper.text()).not.toContain("我的网关");
    expect(wrapper.text()).toContain("还没有保存任何模型配置");
    wrapper.unmount();
  });
});
