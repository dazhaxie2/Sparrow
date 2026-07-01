import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const failures = []

function rel(file) {
  return path.relative(root, file).replaceAll(path.sep, '/')
}

function exists(relativePath) {
  return fs.existsSync(path.join(root, relativePath))
}

function read(relativePath) {
  return fs.readFileSync(path.join(root, relativePath), 'utf8')
}

function fail(message) {
  failures.push(message)
}

function assert(condition, message) {
  if (!condition) fail(message)
}

function walk(relativePath, files = []) {
  const absolute = path.join(root, relativePath)
  if (!fs.existsSync(absolute)) return files
  for (const entry of fs.readdirSync(absolute, { withFileTypes: true })) {
    const full = path.join(absolute, entry.name)
    const relative = rel(full)
    if (entry.isDirectory()) {
      if (/(^|\/)(node_modules|dist|target|__pycache__|\.git|out)$/.test(relative)) continue
      walk(relative, files)
    } else {
      files.push(relative)
    }
  }
  return files
}

assert(!exists('backend/sparrow-chain'), 'backend/sparrow-chain 已退役，不允许重新出现')
assert(exists('backend/sparrow-industry-chain'), '缺少 backend/sparrow-industry-chain 独立服务')
assert(!exists('frontend/src/modules/chains'), 'frontend/src/modules/chains 已退役，请使用 modules/industry-chain')
assert(exists('frontend/src/modules/industry-chain'), '缺少 frontend/src/modules/industry-chain 模块')

const parentPom = read('backend/pom.xml')
assert(parentPom.includes('<module>sparrow-industry-chain</module>'), 'backend/pom.xml 必须纳入 sparrow-industry-chain')
assert(!parentPom.includes('<module>sparrow-chain</module>'), 'backend/pom.xml 不允许再纳入 sparrow-chain')

const dockerfile = read('backend/Dockerfile')
assert(dockerfile.includes('industry-chain-runtime'), 'backend/Dockerfile 必须提供 industry-chain-runtime')
assert(!dockerfile.includes('sparrow-chain'), 'backend/Dockerfile 不允许引用 sparrow-chain')

const compose = read('docker-compose.yml')
assert(compose.includes('sparrow-industry-chain'), 'docker-compose.yml 必须包含 sparrow-industry-chain 服务')
assert(compose.includes('target: industry-chain-runtime'), 'docker-compose.yml 的产业链服务必须使用 industry-chain-runtime')
assert(!compose.includes('sparrow-chain'), 'docker-compose.yml 不允许引用 sparrow-chain')
assert(!compose.includes('sparrow_chain'), 'docker-compose.yml 不允许引用旧 sparrow_chain 库')

const gateway = read('backend/sparrow-gateway/src/main/resources/application.yml')
assert(gateway.includes('lb://sparrow-industry-chain'), 'gateway /api/chains/** 必须路由到 sparrow-industry-chain')
assert(!gateway.includes('lb://sparrow-chain'), 'gateway 不允许再路由到 sparrow-chain')

const router = read('frontend/src/app/router/index.ts')
assert(router.includes("modules/industry-chain/routes"), '前端路由必须从 modules/industry-chain/routes 引入产业链模块')
assert(!router.includes('modules/chains/routes'), '前端路由不允许再引用 modules/chains/routes')

const deploy = read('.github/workflows/deploy.yml')
assert(deploy.includes('sparrow-industry-chain'), 'deploy workflow 必须构建/部署 sparrow-industry-chain')
assert(!deploy.includes('sparrow-chain'), 'deploy workflow 不允许构建/部署 sparrow-chain')

const activeFiles = [
  ...walk('backend').filter(file => !file.includes('/scripts/migrate-industry-chain.sql')),
  ...walk('frontend/src'),
  ...walk('tools/sparrow-spider'),
  '.github/workflows/deploy.yml',
  'docker-compose.yml',
]

const forbidden = [
  ['/api/ai/chain-research', '产业链调研不允许回到 sparrow-ai API'],
  ['lb://sparrow-chain', 'gateway 不允许回到旧静态产业链服务'],
  ['SPARROW_CHAIN', '旧静态产业链库环境变量已退役'],
  ['sparrow_chain', '旧静态产业链库已退役'],
  ['modules/chains', '前端旧 chains 模块已退役'],
  ['fetchChains', '静态产业链列表 API 已退役'],
  ['useChainGraph', '静态产业链图谱 composable 已退役'],
  ['ChainDetailView', '静态产业链详情页已退役'],
  ['ChainListView', '旧产业链双区首页已退役'],
  ['ChainResearchWorkbenchView', '旧工作台命名已退役'],
  ['--chains', 'spider 静态产业链导出命令已退役'],
  ['chain_sync', 'spider 静态产业链同步链路已退役'],
  ['chain_extractor', 'spider 静态产业链抽取链路已退役'],
  ['chain_seeds', 'spider 静态产业链种子已退役'],
]

for (const file of activeFiles) {
  const absolute = path.join(root, file)
  if (!fs.existsSync(absolute)) continue
  const content = fs.readFileSync(absolute, 'utf8')
  for (const [needle, reason] of forbidden) {
    if (content.includes(needle)) {
      fail(`${file} 包含禁用内容 "${needle}"：${reason}`)
    }
  }
}

if (failures.length) {
  console.error('\nArchitecture guard failed:\n')
  for (const message of failures) console.error(`- ${message}`)
  process.exit(1)
}

console.log('Architecture guard passed.')
