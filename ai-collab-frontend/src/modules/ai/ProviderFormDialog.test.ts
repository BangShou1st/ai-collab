// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils"
import { beforeEach, describe, expect, it, vi } from "vitest"
import ProviderFormDialog from "./ProviderFormDialog.vue"
import { BUILTIN_PROVIDER_PROFILES } from "./provider-profiles"

const mocks = vi.hoisted(() => ({
  create: vi.fn(),
  update: vi.fn(),
  models: vi.fn(),
  discoverUnsavedModels: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
}))

vi.mock("./user-ai-api", () => ({
  userAiApi: {
    create: mocks.create,
    update: mocks.update,
    models: mocks.models,
    discoverUnsavedModels: mocks.discoverUnsavedModels,
  },
}))
vi.mock("element-plus", () => ({
  ElMessage: { success: mocks.success, warning: mocks.warning, error: mocks.error },
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
  ElAlert: { props: ["title", "description"], template: '<div class="el-alert-stub">{{ title }}<slot /></div>' },
}

function mountDialog(props: { visible: boolean; editing: any; profiles?: any }) {
  return mount(ProviderFormDialog, {
    props: { profiles: props.profiles ?? BUILTIN_PROVIDER_PROFILES, editing: props.editing, visible: props.visible },
    global: { stubs },
  })
}

// 字段顺序（无发现列表时）：0 名称、1 API Key、2 模型、3 Base URL（高级）、4 API Path（高级）
beforeEach(() => {
  vi.resetAllMocks()
  mocks.create.mockResolvedValue({ data: {} })
  mocks.update.mockResolvedValue({ data: {} })
  mocks.models.mockResolvedValue({ data: ["m1", "m2"] })
  mocks.discoverUnsavedModels.mockResolvedValue({ data: ["m1", "m2"] })
})

describe("ProviderFormDialog provider profiles", () => {
  it("prefills the OpenAI-compatible defaults when opened for creation", async () => {
    const wrapper = mountDialog({ visible: true, editing: null })
    await flushPromises()
    const baseInput = wrapper.findAll("input.el-input-stub")[3]!
    expect((baseInput.element as HTMLInputElement).value).toBe("https://api.openai.com")
    wrapper.unmount()
  })

  it("switches default endpoints when the provider profile changes to GEMINI", async () => {
    const wrapper = mountDialog({ visible: true, editing: null })
    await flushPromises()
    const select = wrapper.find("select.el-select-stub")
    await select.setValue("GEMINI")
    await flushPromises()
    const baseInput = wrapper.findAll("input.el-input-stub")[3]!
    expect((baseInput.element as HTMLInputElement).value).toBe("https://generativelanguage.googleapis.com")
    wrapper.unmount()
  })

  it("prefills ModelScope defaults that avoid the duplicated /v1 junction", async () => {
    const wrapper = mountDialog({ visible: true, editing: null })
    await flushPromises()
    const select = wrapper.find("select.el-select-stub")
    await select.setValue("MODELSCOPE")
    await flushPromises()
    const baseInput = wrapper.findAll("input.el-input-stub")[3]!
    const apiPathInput = wrapper.findAll("input.el-input-stub")[4]!
    expect((baseInput.element as HTMLInputElement).value).toBe("https://api-inference.modelscope.cn")
    expect((apiPathInput.element as HTMLInputElement).value).toBe("/v1/chat/completions")
    // 预填组合不得触发重复版本前缀警告
    expect(wrapper.find(".el-alert-stub").exists()).toBe(false)
    wrapper.unmount()
  })

  it("does not overwrite a user-edited base URL when switching provider profile", async () => {
    const wrapper = mountDialog({ visible: true, editing: null })
    await flushPromises()
    const baseInput = wrapper.findAll("input.el-input-stub")[3]!
    await baseInput.setValue("https://my-gateway.example.com/v1")
    const select = wrapper.find("select.el-select-stub")
    await select.setValue("ANTHROPIC")
    await flushPromises()
    // 用户改过的地址必须保留
    expect((baseInput.element as HTMLInputElement).value).toBe("https://my-gateway.example.com/v1")
    wrapper.unmount()
  })

  it("does not overwrite a user-edited api path when switching provider profile", async () => {
    const wrapper = mountDialog({ visible: true, editing: null })
    await flushPromises()
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
    const baseInput = wrapper.findAll("input.el-input-stub")[3]!
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
    const keyInput = wrapper.findAll("input.el-input-stub")[1]!
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
    await nameInput.setValue("网关")
    const keyInput = wrapper.findAll("input.el-input-stub")[1]!
    await keyInput.setValue("sk-test")
    const modelInput = wrapper.findAll("input.el-input-stub")[2]!
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

  it("discovers models for an unsaved config via the typed key", async () => {
    const wrapper = mountDialog({ visible: true, editing: null })
    await flushPromises()
    const keyInput = wrapper.findAll("input.el-input-stub")[1]!
    await keyInput.setValue("sk-test")
    const discoverButton = wrapper.findAll("button").find((b) => (b.attributes("title") ?? "").includes("获取模型列表"))
    await discoverButton!.trigger("click")
    await flushPromises()
    expect(mocks.discoverUnsavedModels).toHaveBeenCalledWith({
      providerType: "OPENAI_COMPATIBLE",
      baseUrl: "https://api.openai.com",
      apiPath: "/v1/chat/completions",
      apiKey: "sk-test",
    })
    // 发现成功后模型字段变为可搜索选择
    expect(wrapper.findAll("select.el-select-stub").length).toBe(2)
    wrapper.unmount()
  })

  it("falls back to manual model input when discovery fails", async () => {
    mocks.discoverUnsavedModels.mockRejectedValue(new Error("boom"))
    const wrapper = mountDialog({ visible: true, editing: null })
    await flushPromises()
    const keyInput = wrapper.findAll("input.el-input-stub")[1]!
    await keyInput.setValue("sk-test")
    const discoverButton = wrapper.findAll("button").find((b) => (b.attributes("title") ?? "").includes("获取模型列表"))
    await discoverButton!.trigger("click")
    await flushPromises()
    // 发现失败不阻断：模型字段回到手动输入（页面只剩服务商一个下拉）
    expect(wrapper.findAll("select.el-select-stub").length).toBe(1)
    wrapper.unmount()
  })
})

describe("ProviderFormDialog duplicate version prefix warning", () => {
  it("warns before save and offers one-click fixes for the /v1 + /v1 combination", async () => {
    const wrapper = mountDialog({ visible: true, editing: null })
    await flushPromises()
    const baseInput = wrapper.findAll("input.el-input-stub")[3]!
    await baseInput.setValue("https://api-inference.modelscope.cn/v1")
    await flushPromises()
    // 保存前即出现明确提示（0 次网络请求）
    expect(wrapper.find(".el-alert-stub").exists()).toBe(true)
    const fixBase = wrapper.findAll("button").find((b) => b.text().includes("Base URL 不带"))
    const fixPath = wrapper.findAll("button").find((b) => b.text().includes("API Path 不带"))
    expect(fixBase).toBeTruthy()
    expect(fixPath).toBeTruthy()
    // 一键修正（API Path 方向）：去掉重复版本前缀
    await fixPath!.trigger("click")
    await flushPromises()
    expect((wrapper.findAll("input.el-input-stub")[4]!.element as HTMLInputElement).value).toBe("/chat/completions")
    expect(wrapper.find(".el-alert-stub").exists()).toBe(false)
    wrapper.unmount()
  })

  it("keeps the endpoint preview visible in advanced settings", async () => {
    const wrapper = mountDialog({ visible: true, editing: null })
    await flushPromises()
    const preview = wrapper.find('[data-test="endpoint-preview"]')
    expect(preview.exists()).toBe(true)
    expect(preview.text()).toContain("https://api.openai.com/v1/chat/completions")
    expect(preview.text()).toContain("https://api.openai.com/v1/models")
    wrapper.unmount()
  })
})
