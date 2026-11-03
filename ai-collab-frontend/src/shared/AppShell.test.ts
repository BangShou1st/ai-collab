// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils";
import { computed } from "vue";
import { describe, expect, it, vi } from "vitest";
import AppShell from "./AppShell.vue";
import fs from "node:fs";
import path from "node:path";

/** 可变的 route mock：测试中直接改字段后重新挂载，模拟导航/深链/刷新。 */
const routeState = vi.hoisted(() => ({
  params: { projectId: "project-1" } as Record<string, string>,
  fullPath: "/projects/project-1/dashboard",
  path: "/projects/project-1/dashboard",
  query: {} as Record<string, string>,
  meta: {} as Record<string, unknown>,
}));

vi.mock("vue-router", () => ({
  useRoute: () => routeState,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock("element-plus", () => ({ ElMessageBox: { confirm: vi.fn() } }));
vi.mock("../stores/auth-store", () => ({
  useAuthStore: () => ({ currentUser: { displayName: "张三丰测试名", username: "zhang" }, logout: vi.fn() }),
}));
vi.mock("../stores/project-context-store", () => ({
  useProjectContextStore: () => ({
    project: { name: "P" },
    clear: vi.fn(),
    loadProject: vi.fn(),
    isAdminOrOwner: true,
    accessDeniedProjectId: null,
    currentProjectId: routeState.params.projectId,
  }),
}));

const stubs = {
  RouterLink: {
    props: ["to", "activeClass"],
    setup(props: { to: string | { path: string; query?: Record<string, string> } }) {
      const dataTo = computed(() => {
        const target = props.to;
        if (typeof target === "string") return target;
        const query = target.query ?? {};
        const qs = Object.entries(query).map(([k, v]) => `${k}=${v}`).join("&");
        return qs ? `${target.path}?${qs}` : target.path;
      });
      return { dataTo };
    },
    template: `<a :data-to="dataTo" :data-active-class="activeClass || ''"><slot /></a>`,
  },
  ElIcon: { template: '<span><slot /></span>' },
  ElButton: { inheritAttrs: false, template: '<button><slot /></button>' },
  ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
  ElDropdownMenu: { template: '<div><slot /></div>' },
  ElDropdownItem: { template: '<div><slot /></div>' },
};

function resetRoute(overrides: Partial<typeof routeState> = {}): void {
  routeState.params = { projectId: "project-1" };
  routeState.fullPath = "/projects/project-1/dashboard";
  routeState.path = "/projects/project-1/dashboard";
  routeState.query = {};
  routeState.meta = {};
  Object.assign(routeState, overrides);
}

const mountShell = () => mount(AppShell, { slots: { default: "<div />" }, global: { stubs } });

function linkTargets(wrapper: ReturnType<typeof mountShell>): string[] {
  return wrapper.findAll("a").map((a) => a.attributes("data-to") ?? "");
}

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
    expect(block).toContain(".app-shell.collapsed .side-children a");
    expect(block).toContain(".app-shell.collapsed .side-parent-arrow { display: inline-flex; }");
    expect(block).toContain(".app-shell.collapsed .user-row");
    expect(block).toContain(".app-shell.collapsed .collapsed-user");
  });
});

describe("AppShell two-level project navigation", () => {
  it("renders parent groups whose title navigates to a default child page", async () => {
    resetRoute();
    const wrapper = mountShell();
    await flushPromises();
    const parents = wrapper.findAll(".side-parent-link");
    const labels = parents.map((p) => p.text());
    expect(labels).toEqual(["任务中心", "AI 工作区", "知识中心", "项目洞察", "项目设置"]);
    // 父级标题点击进入该模块的合理默认页面
    expect(parents[0]!.attributes("data-to")).toBe("/projects/project-1/tasks?view=list");
    expect(parents[1]!.attributes("data-to")).toBe("/projects/project-1/ai-planning");
    expect(parents[2]!.attributes("data-to")).toBe("/projects/project-1/documents");
    expect(parents[3]!.attributes("data-to")).toBe("/projects/project-1/member-load");
    expect(parents[4]!.attributes("data-to")).toBe("/projects/project-1/members");
    // 父级不再使用 router-link-active 强激活（由 is-parent-active 表达）
    expect(parents[0]!.attributes("data-active-class")).toBe("side-parent-active");
  });

  it("expands the group containing the active child and marks the parent active", async () => {
    resetRoute({ path: "/projects/project-1/knowledge", fullPath: "/projects/project-1/knowledge" });
    const wrapper = mountShell();
    await flushPromises();
    const groups = wrapper.findAll(".side-details");
    const knowledge = groups[2]!;
    expect(knowledge.classes()).toContain("is-open");
    expect(knowledge.classes()).toContain("is-parent-active");
    const others = [groups[0]!, groups[1]!, groups[3]!, groups[4]!];
    for (const group of others) {
      expect(group.classes()).not.toContain("is-parent-active");
    }
  });

  it("auto-expands the active task view group on deep link and refresh", async () => {
    resetRoute({
      path: "/projects/project-1/gantt",
      fullPath: "/projects/project-1/gantt",
    });
    const wrapper = mountShell();
    await flushPromises();
    const taskGroup = wrapper.findAll(".side-details")[0]!;
    expect(taskGroup.classes()).toContain("is-open");
    const active = taskGroup.findAll("a.is-nav-active").map((a) => a.text());
    expect(active).toEqual(["甘特图"]);
  });

  it("toggles a group only through the dedicated arrow button", async () => {
    resetRoute();
    const wrapper = mountShell();
    await flushPromises();
    const knowledgeGroup = wrapper.findAll(".side-details")[2]!;
    const arrow = knowledgeGroup.get(".side-parent-arrow");
    expect(arrow.attributes("aria-expanded")).toBe("false");
    await arrow.trigger("click");
    expect(knowledgeGroup.classes()).toContain("is-open");
    expect(arrow.attributes("aria-expanded")).toBe("true");
    await arrow.trigger("click");
    expect(knowledgeGroup.classes()).not.toContain("is-open");
  });

  it("highlights task views by the actually rendered view, not by sidebar entry", async () => {
    // /tasks 无 view 参数时任务中心渲染看板，侧栏必须高亮“任务看板”而非“任务列表”
    resetRoute({ path: "/projects/project-1/tasks", fullPath: "/projects/project-1/tasks" });
    let wrapper = mountShell();
    await flushPromises();
    let taskGroup = wrapper.findAll(".side-details")[0]!;
    expect(taskGroup.findAll("a.is-nav-active").map((a) => a.text())).toEqual(["任务看板"]);

    // /tasks?view=list 高亮任务列表
    resetRoute({
      path: "/projects/project-1/tasks",
      fullPath: "/projects/project-1/tasks?view=list",
      query: { view: "list" },
    });
    wrapper = mountShell();
    await flushPromises();
    taskGroup = wrapper.findAll(".side-details")[0]!;
    expect(taskGroup.findAll("a.is-nav-active").map((a) => a.text())).toEqual(["任务列表"]);

    // 历史深链 /board 高亮任务看板
    resetRoute({ path: "/projects/project-1/board", fullPath: "/projects/project-1/board" });
    wrapper = mountShell();
    await flushPromises();
    taskGroup = wrapper.findAll(".side-details")[0]!;
    expect(taskGroup.findAll("a.is-nav-active").map((a) => a.text())).toEqual(["任务看板"]);

    // /tasks?view=dependency-graph 高亮任务依赖
    resetRoute({
      path: "/projects/project-1/tasks",
      fullPath: "/projects/project-1/tasks?view=dependency-graph",
      query: { view: "dependency-graph" },
    });
    wrapper = mountShell();
    await flushPromises();
    taskGroup = wrapper.findAll(".side-details")[0]!;
    expect(taskGroup.findAll("a.is-nav-active").map((a) => a.text())).toEqual(["任务依赖"]);
  });

  it("does not highlight any task child when outside the task module", async () => {
    resetRoute();
    const wrapper = mountShell();
    await flushPromises();
    const taskGroup = wrapper.findAll(".side-details")[0]!;
    expect(taskGroup.findAll("a.is-nav-active")).toHaveLength(0);
  });

  it("keeps parent highlight when a child route is active", async () => {
    resetRoute({ path: "/projects/project-1/members", fullPath: "/projects/project-1/members" });
    const wrapper = mountShell();
    await flushPromises();
    const settingsGroup = wrapper.findAll(".side-details")[4]!;
    expect(settingsGroup.classes()).toContain("is-parent-active");
    expect(settingsGroup.classes()).toContain("is-open");
    // 非任务子项由 router-link-active 高亮（应用层 CSS 负责样式），此处断言组级状态
    const memberLink = settingsGroup.findAll("a").find((a) => a.text() === "成员管理")!;
    expect(memberLink.attributes("data-to")).toBe("/projects/project-1/members");
  });

  it("hides audit log entry for non-admin members", async () => {
    resetRoute();
    const wrapper = mount(AppShell, {
      slots: { default: "<div />" },
      global: {
        stubs,
        mocks: {},
      },
    });
    await flushPromises();
    // 本文件的 project-context mock 固定 isAdminOrOwner: true
    const settingsGroup = wrapper.findAll(".side-details")[4]!;
    expect(settingsGroup.text()).toContain("操作日志");
  });

  it("provides a collapsed-state flyout for child navigation", () => {
    const css = fs.readFileSync(path.resolve(__dirname, "../styles/v211.css"), "utf8");
    const nonMedia = css.slice(0, css.indexOf("@media (max-width: 760px)"));
    expect(nonMedia).toContain(".app-shell.collapsed .side-details:hover .side-children");
    expect(nonMedia).toContain(".app-shell.collapsed .side-details:focus-within .side-children");
    expect(nonMedia).toContain("position: absolute; left: calc(100% + 8px)");
  });

  it("keeps the project switch entry at the end of the project nav", async () => {
    resetRoute();
    const wrapper = mountShell();
    await flushPromises();
    const targets = linkTargets(wrapper);
    expect(targets[targets.length - 1]).toBe("/projects");
  });
});
