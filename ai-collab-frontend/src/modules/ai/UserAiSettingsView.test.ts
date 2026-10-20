// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils"
import UserAiSettingsView from "./UserAiSettingsView.vue"
import { beforeEach, describe, expect, it, vi } from "vitest"
import type { UserAiProvider } from "./user-ai-api"

const authState = vi.hoisted(() => ({
  currentUser: { id: "u-1", systemAdmin: false } as { id: string; systemAdmin: boolean } | null,
}))

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  listPurposes: vi.fn(),
  test: vi.fn(),
  testCapability: vi.fn(),
  presets: vi.fn(),
  models: vi.fn(),
  presetTest: vi.fn(),
  disconnect: vi.fn(),
  confirm: vi.fn(),
  success: vi.fn(),
}));

vi.mock("../../stores/auth-store", () => ({ useAuthStore: () => authState }));
vi.mock("./user-ai-api", () => ({
  userAiApi: {
    list: mocks.list, listPurposes: mocks.listPurposes,
    test: mocks.test, testCapability: mocks.testCapability,
    create: vi.fn(), update: vi.fn(), remove: vi.fn(), setDefault: vi.fn(),
    assignPurpose: vi.fn(), unassignPurpose: vi.fn(),
  },
}));
vi.mock("./ai-preset-api", () => ({
  aiPresetApi: { presets: mocks.presets, models: mocks.models, test: mocks.presetTest, save: vi.fn(), disconnect: mocks.disconnect },
}));
vi.mock("element-plus", () => ({
  ElMessage: { success: mocks.success, error: vi.fn() },
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
  capabilities: ["CHAT"], updatedAt: "2026-10-20T00:00:00Z", ...over,
});

// 命名 stub 便于按 aria-label 定位“测试能力”选择器。
const ElSelect = {
  name: "ElSelect",
  inheritAttrs: false,
  props: ["modelValue"],
  emits: ["update:modelValue"],
  template: '<div v-bind="$attrs" class="el-select-stub"><slot /></div>',
};
const ElTag = { name: "ElTag", props: ["type", "size"], template: '<span class="el-tag-stub"><slot /></span>' };
const ElAlert = {
  name: "ElAlert",
  props: ["title", "description", "type"],
  template: '<div class="el-alert-stub">{{ title }} {{ description }}<slot /></div>',
};
const stubs = {
  PageHeader: { template: "<header />" },
  EmptyState: { template: "<div />" },
  ElButton: { inheritAttrs: false, template: '<button v-bind="$attrs"><slot /></button>' },
  ElCard: { template: '<section><slot name="header" /><slot /></section>' },
  ElInput: { template: "<textarea />" },
  ElSelect,
  ElOption: { name: "ElOption", props: ["label", "value", "disabled"], template: '<span class="el-option-stub">{{ label }}</span>' },
  ElSkeleton: { template: "<div />" },
  ElCheckbox: { template: "<span><slot /></span>" },
  ElDialog: { template: "<div><slot /></div>" },
  ElForm: { template: "<form><slot /></form>" },
  ElFormItem: { template: "<label><slot /></label>" },
  ElSwitch: { template: "<span />" },
  ElCollapse: { template: "<div><slot /></div>" },
  ElCollapseItem: { template: "<div><slot /></div>" },
  ElInputNumber: { template: "<input />" },
  ElTag,
  ElAlert,
  RouterLink: { props: ["to"], template: '<a :href="typeof to === \'string\' ? to : to?.path"><slot /></a>' },
};

function mountView() {
  return mount(UserAiSettingsView, { global: { directives: { loading: () => undefined }, stubs } });
}

function buttonByText(wrapper: ReturnType<typeof mountView>, text: string) {
  return wrapper.findAll("button").find((b) => b.text() === text);
}

function probeSelect(wrapper: ReturnType<typeof mountView>) {
  return wrapper.findAllComponents(ElSelect).find((c) => c.attributes("aria-label") === "测试能力");
}

beforeEach(() => {
  vi.resetAllMocks();
  authState.currentUser = { id: "u-1", systemAdmin: false };
  mocks.list.mockResolvedValue(response([]));
  mocks.listPurposes.mockResolvedValue(response({}));
  mocks.presets.mockResolvedValueOnce(response([zen(), kilo()]));
  mocks.presets.mockResolvedValue(response([zen({ connected: false, modelName: null, hasApiKey: false, isDefault: false }), kilo()]));
  mocks.models.mockResolvedValue(response(["mimo-v2.5-free"]));
  mocks.test.mockResolvedValue(response({}));
  mocks.testCapability.mockResolvedValue(response({}));
  mocks.presetTest.mockResolvedValue(response(null));
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
    expect(mocks.success).toHaveBeenCalledWith("已断开 OpenCode Zen 连接");
    expect(buttonByText(wrapper, "断开连接")).toBeFalsy();
    wrapper.unmount();
  });

  it("connects and disconnects Kilo Gateway Free without an API Key", async () => {
    mocks.presets.mockReset();
    mocks.presets.mockResolvedValue(response([
      zen({ connected: false, modelName: null, hasApiKey: false, isDefault: false }),
      kilo({ connected: true, modelName: "poolside/laguna-s-2.1:free", isDefault: true }),
    ]));
    mocks.models.mockResolvedValue(response(["poolside/laguna-s-2.1:free"]));
    const wrapper = mountView();
    await flushPromises();
    expect(mocks.models).toHaveBeenCalledWith("KILO_GATEWAY_FREE", false);
    expect(wrapper.text()).toContain("Kilo Gateway");
    const disconnectButtons = wrapper.findAll("button").filter((b) => b.text() === "断开连接");
    expect(disconnectButtons.length).toBe(1);
    await disconnectButtons[0]!.trigger("click");
    await flushPromises();
    expect(mocks.disconnect).toHaveBeenCalledWith("KILO_GATEWAY_FREE");
    expect(mocks.success).toHaveBeenCalledWith("已断开 Kilo Gateway 连接");
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
    expect(wrapper.text()).not.toContain("未配置");
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
    expect(mocks.test).not.toHaveBeenCalled();
    expect(mocks.testCapability).not.toHaveBeenCalled();
    expect(mocks.presetTest).not.toHaveBeenCalled();
    // 模型目录列举不是真实补全探测
    expect(mocks.models).toHaveBeenCalled();
    wrapper.unmount();
  });

  it("describes the resolved default per purpose and marks it unverified", async () => {
    mocks.list.mockResolvedValue(response([customProvider()]));
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain("默认模型：我的网关 · my-model · 已启用 · 可用性未验证");
    expect(wrapper.text()).toContain("未测试");
    expect(wrapper.text()).not.toContain("已就绪");
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
    // 失效覆盖显示可读名称，而不是原始 Provider ID
    expect(wrapper.text()).toContain("停用覆盖（已停用）");
    expect(wrapper.text()).not.toContain("o-1");
    wrapper.unmount();
  });

  it("does not let one capability result stand in for another", async () => {
    mocks.list.mockResolvedValue(response([customProvider()]));
    const wrapper = mountView();
    await flushPromises();
    await buttonByText(wrapper, "测试")?.trigger("click");
    await flushPromises();
    expect(mocks.test).toHaveBeenCalledWith("c-1");
    expect(wrapper.text()).toContain("连接：本次会话测试通过");

    const select = probeSelect(wrapper);
    expect(select).toBeTruthy();
    select!.vm.$emit("update:modelValue", "STREAMING");
    await flushPromises();
    // 切换能力后，连接结果不再代表当前能力
    expect(wrapper.text()).toContain("未测试");

    await buttonByText(wrapper, "测试")?.trigger("click");
    await flushPromises();
    expect(mocks.testCapability).toHaveBeenCalledWith("c-1", "STREAMING");
    expect(wrapper.text()).toContain("流式：本次会话测试通过");

    select!.vm.$emit("update:modelValue", "CONNECTIVITY");
    await flushPromises();
    expect(wrapper.text()).toContain("连接：本次会话测试通过");
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
});
