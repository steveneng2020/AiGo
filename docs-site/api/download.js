// 「下载 APK」直链端点：302 到最新正式版 Release 的 arm64 或 universal APK。
//
// 资产命名由 .github/workflows/android-release.yml 的 Rename 步骤决定，形如：
//   AiGo-<abi>-<versionName>-<summary>.apk
// 其中 versionName 带构建日期与 commit hash（如 1.0.0-dev+350da83.20261121），
// summary 是提交标题 ASCII 化后的片段 —— 两者都无法在客户端这里预拼，
// 所以必须从 Release 页面的资产列表里读出真实文件名。
const REPO = 'steveneng2020/AiGo'
const LATEST_PAGE = `https://github.com/${REPO}/releases/latest`

// 国内直连 GitHub 下载资产常常龟速，默认套一层 gh-proxy 镜像；
// 镜像探测不通（下线/故障）时退回 GitHub 原链。
const MIRROR_PREFIX = 'https://v6.gh-proxy.org/'
const UA = 'aigo-docs-download'
// 单次外部请求上限，避免跨境慢链路把函数拖到平台超时。
const TIMEOUT_MS = 5000

function request(url, method = 'GET') {
  return fetch(url, {
    method,
    redirect: 'manual',
    headers: { 'user-agent': UA },
    signal: AbortSignal.timeout(TIMEOUT_MS),
  })
}

async function reachable(url) {
  try {
    return (await request(url, 'HEAD')).status < 400
  } catch {
    return false
  }
}

// 从 Release 页面的 HTML 里挑出目标资产的下载路径。
// 优先 arm64（真机），没有则退 universal（全架构）。
function pickAssetPath(html, tag) {
  const escaped = tag.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const pattern = new RegExp(
    `/releases/download/${escaped}/(AiGo-[^"'\\s<>]+\\.apk)`,
    'g'
  )
  const names = new Set()
  let m
  while ((m = pattern.exec(html)) !== null) names.add(m[1])

  const prefer = (prefix) =>
    [...names].find((n) => n.startsWith(prefix) && !n.includes('mapping'))

  return prefer('AiGo-arm64-') || prefer('AiGo-universal-') || null
}

async function resolveApkUrl() {
  // 不走 GitHub API：未认证接口按 IP 限流 60 次/小时，而 Vercel 的出口 IP 是多项目共享的。
  // /releases/latest 会 302 到 /releases/tag/<tag>，从 Location 取版本号即可，
  // 且 latest 天然跳过 pre-release。
  const page = await request(LATEST_PAGE)
  const tag = page.headers.get('location')?.match(/\/releases\/tag\/([^/?#]+)$/)?.[1]
  if (!tag) return null

  const tagPage = await fetch(`https://github.com/${REPO}/releases/expanded_assets/${tag}`, {
    headers: { 'user-agent': UA },
    signal: AbortSignal.timeout(TIMEOUT_MS),
  })
  if (tagPage.status >= 400) return null

  const asset = pickAssetPath(await tagPage.text(), tag)
  if (!asset) return null

  const githubUrl = `https://github.com/${REPO}/releases/download/${tag}/${asset}`
  if (await reachable(MIRROR_PREFIX + githubUrl)) return MIRROR_PREFIX + githubUrl
  return (await reachable(githubUrl)) ? githubUrl : null
}

export default async function handler(req, res) {
  let url = null
  try {
    url = await resolveApkUrl()
  } catch {
    url = null
  }

  const cacheControl = url
    ? 'public, s-maxage=1800, stale-while-revalidate=86400'
    : 'no-store'
  res.writeHead(302, {
    Location: url || LATEST_PAGE,
    'Cache-Control': cacheControl,
  })
  res.end()
}
