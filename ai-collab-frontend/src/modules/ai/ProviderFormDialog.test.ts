// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils"
import { beforeEach, describe, expect, it, vi } from "vitest"
import ProviderFormDialog from "./ProviderFormDialog.vue"
import { BUILTIN_PROVIDER_PROFILES } from "./provider-profiles"

const mocks = vi.hoisted(() => ({
  create: vi.fn(),
  update: vi.fn(),
  models: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}))

vi.mock("./user-ai-api", () => ({
  userAiApi: { create: mocks.create, update: mocks.update, models: mocks.models },
}))
vi.mock("element-plus", () => ({
  ElMessage: { success: mocks.success, warning: vi.fn(), error: mocks.error },
}))
vi.mock("../../api/api-result", () => ({
  showApiError: vi.fn((error: unknown, action: string) => `${action}失败`),
}))

const stubs = {
  ElButton: { inheritAttrs: false, template: '<button v-bind="$attrs"><slot /></button>' },
  ElInput: { props: ["modelValue"], emits: ["update:modelValue"], template: '<input class="el-input-stub" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  ElSelect: {
    props: ["modelValue"], emits: ["update:modelValue", "change"],
    template: '<select class="el-select-stub" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value); $emit(\'change\', $event.target.value)"><slot /></select>',
  },
  ElOption: { props: ["label", "value"], template: '<option :value="value">{{ label }}</option>' },
  ElCheckbox: { props: ["modelValue"], emits: ["update:modelValue"], template: '<input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />' },
  "el-checkbox-group": { props: ["modelValue"], emits: ["update:modelValue"], template: '<div class="el-checkbox-group-stub"><slot /></div>' },
  ElSwitch: { props: ["modelValue"], emits: ["update:modelValue"], template: '<input type="checkbox" />' },
  ElInputNumber: { props: ["modelValue"], template: '<input class="el-number-stub" />' },
  ElDialog: { props: ["modelValue", "title"], emits: ["update:modelValue"], template: '<div class="el-dialog-stub"><slot /><slot name="footer" /></div>' },
  ElForm: { template: "<form><slot /></form>" },
  ElFormItem: { props: ["label"], template: '<label class="el-form-item-stub">{{ label }}<slot /></label>' },
  ElCollapse: { template: "<div><slot /></div>" },
  ElCollapseItem: { template: "<div><slot /></div>" },
}

function mountDialog(props: { visible: boolean; editing: any; profiles?: any }) {
  return mount(ProviderFormDialog, {
    props: { profiles: props.profiles ?? BUILTIN_PROVIDER_PROFILES, editing: props.editing, visible: props.visible },
    global: { stubs },
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  mocks.create.mockResolvedValue({ data: {} })
  mocks.update.mockResolvedValue({ data: {} })
  mocks.models.mockResolvedValue({ data: ["m1", "m2"] })
})

describe("ProviderFormDialog provider presets", () => {
  it("prefills the OpenAI-compatible defaults when opened for creation", async () => {
    const wrapper = mountDialog({ visible: true, editing: null })
    await flushPromises()
    const baseInput = wrapper.findAll("input.el-input-stub")[1]!
    expect((baseInput.element as HTMLInputElement).value).toBe("https://api.openai.com")
    wrapper.unmount()
  })

  it("switches default endpoints when the provider type changes to GEMINI", async () => {
    const wrapper = mountDialog({ visible: true, editing: null })
    await flushPromises()
    const select = wrapper.find("select.el-select-stub")
    await select.setValue("GEMINI")
    await flushPromises()
    // Base URL 输入框（名称之后）应随 Gemini 档案切换
    const baseInput = wrapper.findAll("input.el-input-stub")[1]!
    expect((baseInput.element as HTMLInputElement).value).toBe("https://generativelanguage.googleapis.com")
    wrapper.unmount()
  })

  it("does not overwrite a user-edited base URL when switching provider type", async () => {
    const wrapper = mountDialog({ visible: true, editing: null })
    await flushPromises()
    const baseInput = wrapper.findAll("input.el-input-stub")[1]!
    await baseInput.setValue("https://my-gateway.example.com/v1")
    const select = wrapper.find("select.el-select-stub")
    await select.setValue("ANTHROPIC")
    await flushPromises()
    // 用户改过的地址必须保留
    expect((baseInput.element as HTMLInputElement).value).toBe("https://my-gateway.example.com/v1")
    wrapper.unmount()
  })

  it("does not overwrite a user-edited api path when switching provider type", async () => {
    const wrapper = mountDialog({ visible: true, editing: null })
    await flushPromises()
    // 高级设置里的 API Path：名称/Base URL/Key/模型之后的第五个 el-input-stub
    const apiPathInput = wrapper.findAll("input.el-input-stub")[4]!
    await apiPathInput.setValue("/custom/path")
    const select = wrapper.find("select.el-select-stub")
    await select.setValue("GEMINI")
    await flushPromises()
    expect((apiPathInput.element as HTMLInputElement).value).toBe("/custom/path")
    wrapper.unmount()
  })

  it("edits an existing config without reapplying profile defaults", async () => {
    const wrapper = mountDialog({
      visible: true,
      editing: {
        id: "c-1", name: "我的 Gemini", presetCode: null, providerType: "GEMINI",
        baseUrl: "https://custom.example.com", apiPath: "/v1beta/models/gemini-x:generateContent",
        hasApiKey: true, modelName: "gemini-x", enabled: true, isDefault: false,
        temperature: 0.3, maxOutputTokens: 800, capabilities: ["CHAT"], updatedAt: "2026-10-20T00:00:00Z",
      },
    })
    await flushPromises()
    const baseInput = wrapper.findAll("input.el-input-stub")[1]!
    expect((baseInput.element as HTMLInputElement).value).toBe("https://custom.example.com")
    const select = wrapper.find("select.el-select-stub")
    await select.setValue("GEMINI")
    await flushPromises()
    // 切回同类型也不得把存量配置改回默认端点
    expect((baseInput.element as HTMLInputElement).value).toBe("https://custom.example.com")
    wrapper.unmount()
  })

  it("submits CHAT+STREAMING by default and keeps an empty key as null on update", async () => {
    const wrapper = mountDialog({
      visible: true,
      editing: {
        id: "c-9", name: "编辑目标", presetCode: null, providerType: "OPENAI_COMPATIBLE",
        baseUrl: "https://api.example.com", apiPath: "/v1/chat/completions",
        hasApiKey: true, modelName: "my-model", enabled: true, isDefault: false,
        temperature: 0.2, maxOutputTokens: 1200, capabilities: ["CHAT"], updatedAt: "2026-10-20T00:00:00Z",
      },
    })
    await flushPromises()
    // 编辑时 Key 留空 → 提交 null（后端保留原 Key）
    const keyInput = wrapper.findAll("input.el-input-stub")[2]!
    expect((keyInput.element as HTMLInputElement).value).toBe("")
    const saveButton = wrapper.findAll("button").find((b) => b.text() === "保存")
    await saveButton!.trigger("click")
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledWith("c-9", expect.objectContaining({ apiKey: null }))
    wrapper.unmount()
  })

  it("creates a config with the default capabilities declared", async () => {
    const wrapper = mountDialog({ visible: true, editing: null })
    await flushPromises()
    const nameInput = wrapper.findAll("input.el-input-stub")[0]!
    // 字段顺序：名称、Base URL、API Key、模型
    await nameInput.setValue("网关")
    const keyInput = wrapper.findAll("input.el-input-stub")[2]!
    await keyInput.setValue("sk-test")
    const modelInput = wrapper.findAll("input.el-input-stub")[3]!
    await modelInput.setValue("my-model")
    const saveButton = wrapper.findAll("button").find((b) => b.text() === "保存")
    await saveButton!.trigger("click")
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(expect.objectContaining({
      capabilities: ["CHAT", "STREAMING"],
      baseUrl: "https://api.openai.com",
      apiPath: "/v1/chat/completions",
    }))
    wrapper.unmount()
  })

  it("falls back to manual model input when discovery fails", async () => {
    mocks.models.mockRejectedValue(new Error("boom"))
    const wrapper = mountDialog({
      visible: true,
      editing: {
        id: "c-1", name: "n", presetCode: null, providerType: "OPENAI_COMPATIBLE",
        baseUrl: "https://api.example.com", apiPath: "/v1/chat/completions",
        hasApiKey: true, modelName: "my-model", enabled: true, isDefault: false,
        temperature: 0.2, maxOutputTokens: 1200, capabilities: ["CHAT"], updatedAt: "2026-10-20T00:00:00Z",
      },
    })
    await flushPromises()
    const discoverButton = wrapper.findAll("button").find((b) => (b.attributes("title") ?? "").includes("获取模型列表"))
    expect(discoverButton).toBeTruthy()
    await discoverButton!.trigger("click")
    await flushPromises()
    // 发现失败不阻断：模型字段回到手动输入（页面只剩 Provider 类型一个下拉）
    expect(wrapper.findAll("select.el-select-stub").length).toBe(1)
    wrapper.unmount()
  })
})
