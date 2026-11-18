// 按 Compose 环境变量优先级选择生产 MQ profile；不输出其他环境配置。
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import { parseEnv } from './prod-config-check.mjs'

export function resolveMqProfile(fileValues, processValues = process.env) {
  const values = { ...fileValues, ...processValues }
  const enabled = values.MQ_ENABLED || ''
  if (!['', 'true', 'false'].includes(enabled)) {
    throw new Error('MQ_ENABLED 必须是 true 或 false')
  }
  return enabled === 'true' ? 'mq' : ''
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    if (process.argv.length !== 3) throw new Error('用法: node prod-compose-profile.mjs <环境文件>')
    const values = parseEnv(readFileSync(process.argv[2], 'utf8'))
    process.stdout.write(resolveMqProfile(values))
  } catch (error) {
    console.error(`生产 Compose profile 选择失败：${error.message}`)
    process.exitCode = 1
  }
}
