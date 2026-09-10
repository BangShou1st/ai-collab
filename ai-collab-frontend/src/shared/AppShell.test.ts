// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import AppShell from "./AppShell.vue";
import fs from "node:fs";
import path from "node:path";

vi.mock("vue-router", () => ({
  useRoute: () => ({ params: { projectId: "project-1" }, fullPath: "/home" }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock("element-plus", () => ({ ElMessageBox: { confirm: vi.fn() } }));
vi.mock("../stores/auth-store", () => ({
  useAuthStore: () => ({ currentUser: { displayName: "张三丰测试名", username: "zhang" }, logout: vi.fn() }),
}));
vi.mock("../stores/project-context-store", () => ({
  useProjectContextStore: () => ({ project: { name: "P" }, clear: vi.fn(), loadProject: vi.fn(), isAdminOrOwner: false }),
}));

const stubs = {
  RouterLink: { template: '<a><slot /></a>' },
  ElIcon: { template: '<span><slot /></span>' },
  ElButton: { inheritAttrs: false, template: '<button><slot /></button>' },
  ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
  ElDropdownMenu: { template: '<div><slot /></div>' },
  ElDropdownItem: { template: '<div><slot /></div>' },
};

const mountShell = () => mount(AppShell, { slots: { default: "<div />" }, global: { stubs } });

describe("AppShell account footer", () => {
  it("shows a unified account row without an orphan logout button", async () => {
    const wrapper = mountShell();
    await flushPromises();
    expect(wrapper.find(".user-row").exists()).toBe(true);
    expect(wrapper.find(".user-row").text()).toContain("张三丰测试名");
    const orphan = wrapper.findAll("button").filter((b) => b.text() === "退出");
    expect(orphan).toHaveLength(0);
    expect(wrapper.text()).toContain("账号设置");
    expect(wrapper.text()).toContain("退出登录");
  });

  it("keeps full nav content in DOM when collapsed", async () => {
    const wrapper = mountShell();
    await flushPromises();
    await wrapper.get(".side-collapse").trigger("click");
    expect(wrapper.find(".collapsed-user").exists()).toBe(true);
    const text = wrapper.text();
    for (const label of ["工作台", "AI 设置", "项目概览", "任务看板"]) {
      expect(text).toContain(label);
    }
  });

  it("decouples the mobile drawer from desktop collapsed rules", () => {
    const css = fs.readFileSync(path.resolve(__dirname, "../styles/v211.css"), "utf8");
    const block = css.slice(css.indexOf("@media (max-width: 760px)"));
    expect(block).toContain(".app-shell.collapsed .side-details a");
    expect(block).toContain(".app-shell.collapsed .user-row");
    expect(block).toContain(".app-shell.collapsed .collapsed-user");
  });
});
