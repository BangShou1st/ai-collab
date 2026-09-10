// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils"
import UserAiSettingsView from "./UserAiSettingsView.vue"
import { beforeEach, describe, expect, it, vi } from "vitest"

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  listPurposes: vi.fn(),
  presets: vi.fn(),
  models: vi.fn(),
  disconnect: vi.fn(),
  confirm: vi.fn(),
  success: vi.fn(),
}));

vi.mock("./user-ai-api", () => ({
  userAiApi: { list: mocks.list, listPurposes: mocks.listPurposes },
}));
vi.mock("./ai-preset-api", () => ({
  aiPresetApi: { presets: mocks.presets, models: mocks.models, disconnect: mocks.disconnect },
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

const stubs = {
  PageHeader: { template: "<header />" },
  EmptyState: { template: "<div />" },
  ElButton: {
    inheritAttrs: false,
    template: '<button v-bind="$attrs"><slot /></button>',
  },
  ElCard: { template: '<section><slot name="header" /><slot /></section>' },
  ElInput: { template: "<textarea />" },
  ElSelect: { template: "<div><slot /></div>" },
  ElOption: { template: "<span />" },
  ElSkeleton: { template: "<div />" },
  ElCheckbox: { template: "<span><slot /></span>" },
  ElDialog: { template: "<div><slot /></div>" },
  ElForm: { template: "<form><slot /></form>" },
  ElFormItem: { template: "<label><slot /></label>" },
  ElSwitch: { template: "<span />" },
  ElCollapse: { template: "<div><slot /></div>" },
  ElCollapseItem: { template: "<div><slot /></div>" },
  ElInputNumber: { template: "<input />" },
};

beforeEach(() => {
  vi.clearAllMocks();
  mocks.list.mockResolvedValue(response([]));
  mocks.listPurposes.mockResolvedValue(response({}));
  mocks.presets.mockResolvedValueOnce(response([{ code: "OPENCODE_ZEN_FREE", displayName: "OpenCode Zen", connected: true, modelName: "mimo-v2.5-free", enabled: true, isDefault: false, hasApiKey: true }]));
  mocks.presets.mockResolvedValue(response([{ code: "OPENCODE_ZEN_FREE", displayName: "OpenCode Zen", connected: false, modelName: null, enabled: true, isDefault: false, hasApiKey: false }]));
  mocks.models.mockResolvedValue(response(["mimo-v2.5-free"]));
  mocks.disconnect.mockResolvedValue(undefined);
  mocks.confirm.mockResolvedValue(true);
});

describe("UserAiSettingsView disconnect", () => {
  it("disconnects Zen with confirm and reloads", async () => {
    const wrapper = mount(UserAiSettingsView, {
      global: { directives: { loading: () => undefined }, stubs },
    });
    await flushPromises();
    const btn = wrapper.findAll("button").find((b) => b.text() === "断开连接");
    expect(btn).toBeTruthy();
    await btn?.trigger("click");
    await flushPromises();
    expect(mocks.confirm).toHaveBeenCalledTimes(1);
    expect(mocks.disconnect).toHaveBeenCalledTimes(1);
    expect(mocks.presets).toHaveBeenCalledTimes(2);
    expect(mocks.listPurposes).toHaveBeenCalledTimes(2);
    expect(mocks.success).toHaveBeenCalledWith("已断开 OpenCode Zen 连接");
    expect(wrapper.findAll("button").some((b) => b.text() === "断开连接")).toBe(false);
  });
  it("shows an error state instead of faking defaults when purposes fail", async () => {
    mocks.listPurposes.mockRejectedValueOnce(new Error("boom"));
    const wrapper = mount(UserAiSettingsView, {
      global: { directives: { loading: () => undefined }, stubs },
    });
    await flushPromises();
    expect(wrapper.findAll("button").some((b) => b.text() === "重试")).toBe(true);
    mocks.listPurposes.mockResolvedValue(response({ KNOWLEDGE_CHAT: "p-1" }));
    await wrapper.findAll("button").find((b) => b.text() === "重试")?.trigger("click");
    await flushPromises();
    expect(wrapper.findAll("button").some((b) => b.text() === "重试")).toBe(false);
  });
});
