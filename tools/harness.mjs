import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const manifest = JSON.parse(fs.readFileSync(path.join(root, '.harness/manifest.json'), 'utf8'))
const serviceNames = manifest.backend.services.map(service => service.name)

function executable(name) {
  return name
}

function needsShell(name) {
  return process.platform === 'win32' && (name === 'npm' || name === 'mvn')
}

function probe(name, args = ['--version']) {
  const result = spawnSync(executable(name), args, {
    cwd: root,
    encoding: 'utf8',
    windowsHide: true,
    shell: needsShell(name),
  })
  return {
    available: !result.error && result.status === 0,
    output: `${result.stdout ?? ''}${result.stderr ?? ''}`.trim(),
  }
}

function run(name, args, options = {}) {
  const cwd = options.cwd ?? root
  const shownCwd = path.relative(root, cwd) || '.'
  console.log(`\n[harness] ${options.label ?? name} (${shownCwd})`)
  console.log(`> ${name} ${args.join(' ')}`)
  const result = spawnSync(executable(name), args, {
    cwd,
    stdio: 'inherit',
    windowsHide: true,
    env: { ...process.env, ...(options.env ?? {}) },
    shell: needsShell(name),
  })
  if (result.error) {
    throw new Error(`${name} could not start: ${result.error.message}`)
  }
  if (result.status !== 0) {
    throw new Error(`${options.label ?? name} failed with exit code ${result.status}`)
  }
}

function git(args, allowFailure = false) {
  const result = spawnSync(executable('git'), args, {
    cwd: root,
    encoding: 'utf8',
    windowsHide: true,
  })
  if (result.error || result.status !== 0) {
    if (allowFailure) return null
    throw new Error(`git ${args.join(' ')} failed: ${(result.stderr ?? result.error?.message ?? '').trim()}`)
  }
  return result.stdout.trim()
}

function addLines(set, output) {
  for (const line of (output ?? '').split(/\r?\n/)) {
    if (line.trim()) set.add(line.trim().replaceAll('\\', '/'))
  }
}

function changedFiles(base) {
  const files = new Set()
  if (base && !/^0+$/.test(base)) {
    const validBase = git(['rev-parse', '--verify', `${base}^{commit}`], true)
    if (!validBase) {
      throw new Error(`Base ref ${base} is unavailable. Fetch history or pass an existing commit/ref.`)
    }
    addLines(files, git(['diff', '--name-only', '--diff-filter=ACMR', `${base}...HEAD`]))
  }
  addLines(files, git(['diff', '--name-only', '--diff-filter=ACMR']))
  addLines(files, git(['diff', '--cached', '--name-only', '--diff-filter=ACMR']))
  addLines(files, git(['ls-files', '--others', '--exclude-standard']))
  return [...files].sort()
}

function runGuards() {
  run(process.execPath, ['tools/harness-guard.mjs'], { label: 'Harness structural guard' })
  run(process.execPath, ['tools/architecture-guard.mjs'], { label: 'Product architecture guard' })
}

function requireFrontendDependencies() {
  if (!fs.existsSync(path.join(root, 'frontend/node_modules'))) {
    throw new Error('Frontend dependencies are missing. Run `npm ci --prefix frontend`, then retry.')
  }
}

function frontendBuild() {
  requireFrontendDependencies()
  run('npm', ['run', 'build'], { cwd: path.join(root, 'frontend'), label: 'Frontend production build' })
}

function pythonSyntax(toolPaths = manifest.pythonTools.map(tool => tool.path)) {
  if (!probe('python').available) {
    throw new Error('Python is required to validate repository tools. Install Python 3.10 or newer.')
  }
  run('python', ['-m', 'compileall', '-q', ...toolPaths], { label: 'Python tool syntax' })
}

function backendTest(modules = []) {
  for (const moduleName of modules) {
    if (!serviceNames.includes(moduleName)) {
      throw new Error(`Unknown backend module ${moduleName}. Expected one of: ${serviceNames.join(', ')}`)
    }
  }
  const selection = modules.length > 0 ? ['-pl', modules.join(','), '-am'] : []
  const resolverArgs = [
    '-Daether.connector.basic.threads=4',
    '-Daether.connector.connectTimeout=30000',
    '-Daether.connector.requestTimeout=120000',
    '-Daether.transport.http.retryHandler.count=5',
  ]
  const mavenArgs = [
    '-f', path.join('backend', 'pom.xml'),
    '-s', path.join('backend', 'docker', 'maven-settings.xml'),
    '-B', '-ntp', ...resolverArgs, ...selection, 'clean', 'test',
  ]
  if (probe('mvn').available) {
    run('mvn', mavenArgs, {
      label: modules.length > 0 ? `Backend tests: ${modules.join(', ')}` : 'Full backend tests',
    })
    return
  }

  if (!probe('docker').available) {
    throw new Error('Neither Maven nor Docker is available. Install Maven with Java 21 or Docker Desktop.')
  }
  const backendRoot = path.join(root, 'backend')
  const dockerMavenArgs = [
    'run', '--rm',
    '-v', `${backendRoot}:/workspace`,
    '-v', 'sparrow_m2:/root/.m2',
    '-w', '/workspace',
    'maven:3.9-eclipse-temurin-21',
    'mvn', '-s', 'docker/maven-settings.xml', '-B', '-ntp',
    ...resolverArgs,
    ...(modules.length > 0 ? ['-pl', modules.join(','), '-am'] : []),
    'clean', 'test',
  ]
  run('docker', dockerMavenArgs, {
    label: modules.length > 0 ? `Backend tests in Docker: ${modules.join(', ')}` : 'Full backend tests in Docker',
  })
}

function composeConfig(required = true) {
  if (!probe('docker').available) {
    if (required) throw new Error('Docker is required to validate Compose configuration.')
    console.log('[harness] Docker unavailable; skipped optional Compose validation.')
    return
  }
  run('docker', ['compose', 'config', '--quiet'], { label: 'Docker Compose configuration' })
}

function parseBase(args) {
  const index = args.indexOf('--base')
  if (index === -1) return null
  if (!args[index + 1]) throw new Error('`--base` requires a git ref or commit SHA.')
  const base = args[index + 1]
  args.splice(index, 2)
  return base
}

function verifyChanged(args) {
  const base = parseBase(args)
  if (args.length > 0) throw new Error(`Unknown changed arguments: ${args.join(' ')}`)
  const files = changedFiles(base)
  console.log(`[harness] ${files.length} changed file(s) in validation scope.`)
  for (const file of files) console.log(`  ${file}`)

  runGuards()
  if (files.length === 0) {
    console.log('[harness] No changed files detected; deterministic guards were sufficient.')
    return
  }

  const frontendChanged = files.some(file => file.startsWith('frontend/'))
  const backendFiles = files.filter(file => file.startsWith('backend/'))
  const sharedBackendChanged = backendFiles.some(file =>
    file === 'backend/pom.xml' ||
    file === 'backend/Dockerfile' ||
    file.startsWith('backend/docker/') ||
    file.startsWith('backend/sparrow-common/') ||
    file.startsWith('backend/scripts/'))

  const backendModules = new Set()
  for (const file of backendFiles) {
    const match = file.match(/^backend\/(sparrow-[^/]+)\//)
    if (match && serviceNames.includes(match[1])) backendModules.add(match[1])
  }

  const infrastructureChanged = files.some(file =>
    /^docker-compose(?:\.[^/]+)?\.yml$/.test(file) ||
    file === '.env.example' ||
    file.startsWith('deploy/'))
  const changedPythonTools = manifest.pythonTools
    .filter(tool => files.some(file => file.startsWith(`${tool.path}/`)))
    .map(tool => tool.path)

  if (infrastructureChanged) composeConfig(true)
  if (sharedBackendChanged) backendTest([])
  else if (backendModules.size > 0) backendTest([...backendModules].sort())
  if (frontendChanged) frontendBuild()
  if (changedPythonTools.length > 0) pythonSyntax(changedPythonTools)

  if (!frontendChanged && backendFiles.length === 0 && !infrastructureChanged && changedPythonTools.length === 0) {
    console.log('[harness] Only documentation/harness metadata changed; guards completed the required checks.')
  }
}

function doctor() {
  const checks = []
  const node = probe('node')
  const npm = probe('npm')
  const java = probe('java', ['-version'])
  const maven = probe('mvn')
  const docker = probe('docker')
  const gitCheck = probe('git')
  const python = probe('python', ['--version'])

  const nodeMajor = Number(node.output.match(/v?(\d+)\./)?.[1])
  const javaMajor = Number(java.output.match(/version "(?:1\.)?(\d+)/)?.[1])
  const pythonVersion = python.output.match(/Python (\d+)\.(\d+)/)
  const pythonOk = python.available && pythonVersion &&
    (Number(pythonVersion[1]) > 3 || Number(pythonVersion[1]) === 3 && Number(pythonVersion[2]) >= 10)
  checks.push(['Git', gitCheck.available, gitCheck.output.split(/\r?\n/)[0] || 'missing'])
  checks.push([`Node >= ${manifest.toolchain.node}`, node.available && nodeMajor >= manifest.toolchain.node, node.output || 'missing'])
  checks.push(['npm', npm.available, npm.output || 'missing'])
  checks.push(['Python >= 3.10', Boolean(pythonOk), python.output || 'missing'])
  checks.push([`Java >= ${manifest.toolchain.java}`, java.available && javaMajor >= manifest.toolchain.java, java.output.split(/\r?\n/)[0] || 'missing'])
  checks.push(['Maven or Docker', maven.available || docker.available,
    maven.available ? maven.output.split(/\r?\n/)[0] : (docker.output || 'both missing')])
  checks.push(['Frontend dependencies', fs.existsSync(path.join(root, 'frontend/node_modules')),
    fs.existsSync(path.join(root, 'frontend/node_modules')) ? 'installed' : 'run npm ci --prefix frontend'])

  let failed = 0
  console.log('Sparrow harness doctor')
  for (const [name, ok, detail] of checks) {
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}: ${detail}`)
    if (!ok) failed += 1
  }
  if (failed > 0) throw new Error(`${failed} environment check(s) failed.`)
}

function taskInit(args) {
  const [id, ...titleParts] = args
  const title = titleParts.join(' ').trim()
  if (!id || !/^[a-z0-9][a-z0-9-]{1,63}$/.test(id)) {
    throw new Error('Task id must be a 2-64 character lowercase kebab-case slug.')
  }
  if (!title) throw new Error('Provide a task title after the slug.')
  const taskFile = path.join(root, manifest.taskState.directory, `${id}.json`)
  if (fs.existsSync(taskFile)) throw new Error(`Task already exists: ${path.relative(root, taskFile)}`)
  const now = new Date().toISOString()
  const task = {
    schemaVersion: 1,
    id,
    title,
    status: 'draft',
    objective: '',
    scope: { in: [], out: [] },
    acceptanceCriteria: [],
    steps: [],
    decisions: [],
    blockers: [],
    verification: { lastRunAt: null, commands: [] },
    nextAction: 'Define objective, scope and observable acceptance criteria',
    createdAt: now,
    updatedAt: now,
  }
  fs.writeFileSync(taskFile, `${JSON.stringify(task, null, 2)}\n`, { encoding: 'utf8', flag: 'wx' })
  console.log(`Created ${path.relative(root, taskFile)}`)
  console.log('Fill in the draft before changing code; see docs/harness/long-running-tasks.md.')
}

function taskStatus(args) {
  if (args.length > 1) throw new Error('Usage: task:status [task-id]')
  const directory = path.join(root, manifest.taskState.directory)
  const wanted = args[0]
  const files = fs.readdirSync(directory)
    .filter(file => file.endsWith('.json') && file !== 'TEMPLATE.json')
    .filter(file => !wanted || file === `${wanted}.json`)
    .sort()
  if (files.length === 0) {
    console.log(wanted ? `No task found for ${wanted}.` : 'No active task records.')
    return
  }
  for (const file of files) {
    const task = JSON.parse(fs.readFileSync(path.join(directory, file), 'utf8'))
    const passed = task.acceptanceCriteria.filter(item => item.status === 'passed').length
    console.log(`${task.id}  ${task.status}  criteria ${passed}/${task.acceptanceCriteria.length}`)
    console.log(`  ${task.title}`)
    console.log(`  next: ${task.nextAction || '(not recorded)'}`)
    console.log(`  updated: ${task.updatedAt}`)
  }
}

function help() {
  console.log(`Sparrow repository harness

Usage:
  node tools/harness.mjs doctor
  node tools/harness.mjs guard
  node tools/harness.mjs changed [--base <git-ref>]
  node tools/harness.mjs backend [sparrow-module ...]
  node tools/harness.mjs frontend
  node tools/harness.mjs full
  node tools/harness.mjs task:init <slug> <title>
  node tools/harness.mjs task:status [slug]

Run commands from the repository root. See docs/harness/verification.md.`)
}

const [command = 'help', ...args] = process.argv.slice(2)

try {
  switch (command) {
    case 'doctor': doctor(); break
    case 'guard': runGuards(); break
    case 'changed': verifyChanged(args); break
    case 'backend': backendTest(args); break
    case 'frontend': frontendBuild(); break
    case 'full':
      runGuards()
      composeConfig(false)
      backendTest([])
      frontendBuild()
      pythonSyntax()
      break
    case 'task:init': taskInit(args); break
    case 'task:status': taskStatus(args); break
    case 'help':
    case '--help':
    case '-h': help(); break
    default:
      help()
      throw new Error(`Unknown command: ${command}`)
  }
} catch (error) {
  console.error(`\n[harness] FAILED: ${error.message}`)
  process.exit(1)
}
