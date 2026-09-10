import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  activityDisplayLabel,
  auditActionLabel,
  auditEntityLabel,
  documentStatusLabel,
  projectStatusLabel,
  roleLabel,
  taskStatusLabel,
} from './display-labels'

describe('面向用户的中文显示映射', () => {
  it('maps current project, role, task and document values', () => {
    expect(projectStatusLabel('ACTIVE')).toBe('进行中')
    expect(projectStatusLabel('ARCHIVED')).toBe('已归档')
    expect(roleLabel('OWNER')).toBe('所有者')
    expect(taskStatusLabel('BLOCKED')).toBe('已阻塞')
    expect(documentStatusLabel('INDEXING')).toBe('向量化中')
  })

  it('maps audit values that the backend actually emits', () => {
    expect(auditActionLabel('PROJECT_MEMBER_ROLE_CHANGED')).toBe('变更成员角色')
    expect(auditActionLabel('DOCUMENT_INDEXED')).toBe('完成文档处理')
    expect(auditActionLabel('TASK_PLAN_CONFIRMED')).toBe('确认 AI 任务规划')
    expect(auditEntityLabel('PROJECT_MEMBER')).toBe('项目成员')
    expect(auditEntityLabel('PROJECT_DOCUMENT')).toBe('项目文档')
    expect(auditEntityLabel('AI_TASK_PLAN')).toBe('AI 任务规划')
  })

  it('does not expose unknown internal values', () => {
    expect(auditActionLabel('INTERNAL_SECRET_ACTION')).toBe('未知操作')
    expect(auditEntityLabel('RAW_DATABASE_ENTITY')).toBe('未知对象')
  })

  it('never exposes raw activity enums in normal UI', () => {
    expect(activityDisplayLabel('TASK_CREATED')).toBe('创建任务')
    expect(activityDisplayLabel('TASK_PLAN_CREATED')).toBe('创建 AI 任务规划')
    expect(activityDisplayLabel('DOCUMENT_UPLOADED')).toBe('上传文档')
    // 未知系统事件也不得回显 raw enum
    expect(activityDisplayLabel('SOME_FUTURE_EVENT_X')).not.toMatch(/^[A-Z]{2,}_[A-Z0-9_]+$/)
    expect(activityDisplayLabel('')).toBe('动态更新')
  });

  it('keeps GLOBAL PAGE GEOMETRY CONTRACT tokens', () => {
    const tokensPath = path.resolve(__dirname, '../styles/tokens.css')
    const tokens = fs.readFileSync(tokensPath, 'utf8')
    expect(tokens).toContain('--page-max-width: 1240px')
    expect(tokens).toContain('--page-gutter: 40px')
    expect(tokens).toContain('--page-top: 32px')
    expect(tokens).toContain('--sidebar-expanded: 248px')
    expect(tokens).toContain('--sidebar-collapsed: 72px')
    expect(tokens).toContain('--radius-card: 12px')
    expect(tokens).toContain('--radius-dialog: 14px')
  });

  it('keeps tables flat and cards non-elevated by default', () => {
    const overrides = fs.readFileSync(path.resolve(__dirname, '../styles/element-overrides.css'), 'utf8')
    expect(overrides).toContain('box-shadow: none')
    expect(overrides).toContain('.object-card')
  });
})
