/**
 * 手动远程验收脚本的目标地址守卫（不属于任何自动门禁）。
 *
 * - 目标地址只从环境变量 STAGING_BASE_URL 读取，没有任何默认值。
 * - 非 localhost/回环地址必须显式设置 ALLOW_REMOTE_WRITE=1 才允许发请求，
 *   因为这些脚本会对目标环境执行真实注册、建项目、上传等写操作。
 * - 守卫在发出任何网络请求之前执行；不满足条件时打印用法并以非 0 退出。
 */

const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '::1', '[::1]'])

function isLoopback(hostname) {
  const host = hostname.toLowerCase()
  return LOOPBACK_HOSTS.has(host) || host.endsWith('.localhost') || /^127\.\d+\.\d+\.\d+$/.test(host)
}

function fail(scriptName, message) {
  console.error(`错误: ${message}\n`)
  console.error(`用法: STAGING_BASE_URL=<https://目标地址> ALLOW_REMOTE_WRITE=1 node ai-collab-deploy/scripts/manual/${scriptName}`)
  console.error('  STAGING_BASE_URL    必填，目标环境根地址，无默认值')
  console.error('  ALLOW_REMOTE_WRITE  目标不是 localhost/回环地址时必须为 1（脚本会对目标执行真实写操作）')
  process.exit(2)
}

/** 解析并校验目标地址；校验失败时直接退出进程（退出码 2），不会返回。 */
export function resolveTarget(scriptName) {
  const raw = (process.env.STAGING_BASE_URL || '').trim()
  if (!raw) fail(scriptName, '未设置 STAGING_BASE_URL')

  let url
  try {
    url = new URL(raw)
  } catch {
    fail(scriptName, `STAGING_BASE_URL 不是合法 URL: ${raw}`)
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    fail(scriptName, `STAGING_BASE_URL 只支持 http/https: ${raw}`)
  }
  if (!isLoopback(url.hostname) && process.env.ALLOW_REMOTE_WRITE !== '1') {
    fail(scriptName, `目标 ${url.origin} 不是本机地址，拒绝运行；确认要对该环境执行真实写操作时设置 ALLOW_REMOTE_WRITE=1`)
  }
  return url.origin
}
