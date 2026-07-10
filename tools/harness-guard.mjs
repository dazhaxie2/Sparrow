import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const failures = []

function absolute(relativePath) {
  return path.join(root, relativePath)
}

function normalize(file) {
  return file.replaceAll(path.sep, '/')
}

function exists(relativePath) {
  return fs.existsSync(absolute(relativePath))
}

function read(relativePath) {
  return fs.readFileSync(absolute(relativePath), 'utf8')
}

function readJson(relativePath) {
  try {
    return JSON.parse(read(relativePath))
  } catch (error) {
    fail('H001', relativePath, `JSON cannot be parsed: ${error.message}`,
      'Fix the JSON syntax and run `node tools/harness.mjs guard` again.')
    return null
  }
}

function fail(rule, file, message, remediation) {
  failures.push({ rule, file: normalize(file), message, remediation })
}

function check(condition, rule, file, message, remediation) {
  if (!condition) fail(rule, file, message, remediation)
}

function walk(relativePath, predicate = () => true, files = []) {
  const start = absolute(relativePath)
  if (!fs.existsSync(start)) return files
  for (const entry of fs.readdirSync(start, { withFileTypes: true })) {
    const relative = normalize(path.relative(root, path.join(start, entry.name)))
    if (entry.isDirectory()) {
      if (/(^|\/)(node_modules|dist|target|out|__pycache__|\.git)$/.test(relative)) continue
      walk(relative, predicate, files)
    } else if (predicate(relative)) {
      files.push(relative)
    }
  }
  return files
}

function extractDependencyArtifacts(pom) {
  return [...pom.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)]
    .map(match => match[1].match(/<artifactId>([^<]+)<\/artifactId>/)?.[1])
    .filter(Boolean)
}

function trackedFiles() {
  try {
    return execFileSync('git', ['ls-files', '-z'], { cwd: root, encoding: 'utf8' })
      .split('\0')
      .filter(Boolean)
      .map(normalize)
  } catch (error) {
    fail('H002', '.git', `Cannot inspect tracked files: ${error.message}`,
      'Run the guard from a Git checkout with `git` available.')
    return []
  }
}

function validDateTime(value) {
  return typeof value === 'string' && !Number.isNaN(Date.parse(value)) && /T/.test(value)
}

function validateTask(file, task, statuses) {
  if (!task || typeof task !== 'object' || Array.isArray(task)) return
  const required = [
    'schemaVersion', 'id', 'title', 'status', 'objective', 'scope',
    'acceptanceCriteria', 'steps', 'decisions', 'blockers', 'verification',
    'nextAction', 'createdAt', 'updatedAt',
  ]
  for (const key of required) {
    check(Object.hasOwn(task, key), 'T001', file, `Missing required field "${key}".`,
      'Restore the field from `.harness/tasks/TEMPLATE.json`.')
  }
  if (required.some(key => !Object.hasOwn(task, key))) return

  check(task.schemaVersion === 1, 'T002', file, 'schemaVersion must be 1.',
    'Use the current template and migrate the task record explicitly.')
  check(/^[a-z0-9][a-z0-9-]{1,63}$/.test(task.id ?? ''), 'T003', file,
    'id must be a 2-64 character lowercase kebab-case slug.',
    'Rename the id, for example `research-resume`, and keep it aligned with the filename.')
  check(file.endsWith(`/${task.id}.json`), 'T004', file,
    `Filename must match task id "${task.id}".`, 'Rename the file or correct its id.')
  check(typeof task.title === 'string' && task.title.trim().length > 0, 'T005', file,
    'title must not be empty.', 'Add a concise, outcome-oriented title.')
  check(statuses.includes(task.status), 'T006', file,
    `Unknown status "${task.status}".`, `Use one of: ${statuses.join(', ')}.`)
  check(task.scope && Array.isArray(task.scope.in) && Array.isArray(task.scope.out),
    'T007', file, 'scope must contain array fields `in` and `out`.',
    'Restore the scope object from the task template.')
  check(Array.isArray(task.acceptanceCriteria), 'T008', file,
    'acceptanceCriteria must be an array.', 'Restore the array from the task template.')
  check(Array.isArray(task.steps), 'T009', file, 'steps must be an array.',
    'Restore the array from the task template.')
  check(Array.isArray(task.decisions) && Array.isArray(task.blockers), 'T010', file,
    'decisions and blockers must be arrays.', 'Restore both arrays from the task template.')
  check(task.verification && Array.isArray(task.verification.commands), 'T011', file,
    'verification.commands must be an array.', 'Restore verification from the task template.')
  check(validDateTime(task.createdAt) && validDateTime(task.updatedAt), 'T012', file,
    'createdAt and updatedAt must be ISO-8601 timestamps.',
    'Use timestamps such as `2026-07-10T12:00:00.000Z`.')

  if (Array.isArray(task.acceptanceCriteria)) {
    const ids = new Set()
    for (const criterion of task.acceptanceCriteria) {
      const valid = criterion && /^AC-[0-9]+$/.test(criterion.id ?? '') &&
        typeof criterion.description === 'string' && criterion.description.trim() &&
        ['pending', 'passed', 'failed', 'blocked'].includes(criterion.status) &&
        Array.isArray(criterion.evidence)
      check(Boolean(valid), 'T013', file, 'Every acceptance criterion must match the template shape.',
        'Use id `AC-N`, a non-empty description, valid status and evidence array.')
      if (criterion?.id) {
        check(!ids.has(criterion.id), 'T014', file, `Duplicate criterion id ${criterion.id}.`,
          'Give every acceptance criterion a unique sequential id.')
        ids.add(criterion.id)
      }
    }
  }

  if (Array.isArray(task.steps)) {
    const ids = new Set()
    let active = 0
    for (const step of task.steps) {
      const valid = step && /^S-[0-9]+$/.test(step.id ?? '') &&
        typeof step.description === 'string' && step.description.trim() &&
        ['pending', 'in_progress', 'completed', 'blocked'].includes(step.status) &&
        typeof step.verification === 'string'
      check(Boolean(valid), 'T015', file, 'Every step must match the template shape.',
        'Use id `S-N`, a non-empty description, valid status and verification string.')
      if (step?.status === 'in_progress') active += 1
      if (step?.id) {
        check(!ids.has(step.id), 'T016', file, `Duplicate step id ${step.id}.`,
          'Give every step a unique sequential id.')
        ids.add(step.id)
      }
    }
    check(active <= 1, 'T017', file, 'At most one step may be in_progress.',
      'Complete or pause the current step before starting another.')
  }

  if (task.status !== 'draft') {
    check(typeof task.objective === 'string' && task.objective.trim().length > 0,
      'T018', file, 'A non-draft task needs an objective.',
      'State the observable outcome before moving the task out of draft.')
    check(task.acceptanceCriteria?.length > 0, 'T019', file,
      'A non-draft task needs acceptance criteria.',
      'Add at least one independently verifiable criterion.')
    check(task.steps?.length > 0, 'T020', file, 'A non-draft task needs execution steps.',
      'Add small, ordered steps with verification intent.')
    check(typeof task.nextAction === 'string' && task.nextAction.trim().length > 0,
      'T021', file, 'A non-draft task needs one concrete nextAction.',
      'Describe the smallest next action another session can execute.')
  }

  if (task.status === 'completed') {
    check(task.acceptanceCriteria.every(item => item.status === 'passed' && item.evidence.length > 0),
      'T022', file, 'Completed tasks require passed criteria with evidence.',
      'Reopen the task or record evidence for every criterion.')
    check(task.steps.every(step => step.status === 'completed'), 'T023', file,
      'Completed tasks cannot contain pending or blocked steps.',
      'Complete the remaining steps or move them explicitly out of scope.')
    check(task.verification.commands.length > 0, 'T024', file,
      'Completed tasks require recorded verification.',
      'Record exact commands and their real results before completion.')
  }
}

const manifest = readJson('.harness/manifest.json')
if (manifest) {
  check(manifest.schemaVersion === 1, 'H003', '.harness/manifest.json',
    'Unsupported manifest schemaVersion.', 'Use schemaVersion 1 or update the guard with the schema.')

  for (const document of manifest.requiredDocuments ?? []) {
    check(exists(document), 'H004', document, 'Required harness document is missing.',
      'Restore the document or intentionally update `requiredDocuments` and the agent maps.')
  }

  for (const agentFile of ['AGENTS.md', 'backend/AGENTS.md', 'frontend/AGENTS.md']) {
    if (!exists(agentFile)) continue
    const lineCount = read(agentFile).split(/\r?\n/).length
    check(lineCount <= 140, 'H005', agentFile, `Agent map has ${lineCount} lines; limit is 140.`,
      'Move durable detail to `docs/harness/` and keep this file as a navigational map.')
  }

  const services = manifest.backend?.services ?? []
  const serviceNames = services.map(service => service.name)
  const parentPom = exists('backend/pom.xml') ? read('backend/pom.xml') : ''
  const reactorModules = [...parentPom.matchAll(/<module>(sparrow-[^<]+)<\/module>/g)]
    .map(match => match[1])
  check(JSON.stringify(reactorModules) === JSON.stringify(serviceNames), 'B001',
    'backend/pom.xml', 'Maven reactor modules do not match `.harness/manifest.json`.',
    'Update the parent POM, manifest and architecture map together; preserve intentional order.')

  const internalNames = new Set(serviceNames)
  for (const service of services) {
    check(exists(service.path), 'B002', service.path, `Module ${service.name} is missing.`,
      'Restore the module or remove it from the POM, manifest and deployment topology together.')
    check(exists(`${service.path}/pom.xml`), 'B003', `${service.path}/pom.xml`,
      `Module ${service.name} has no pom.xml.`, 'Add its Maven descriptor or correct the manifest path.')
    if (service.applicationConfigRequired) {
      check(exists(`${service.path}/src/main/resources/application.yml`), 'B004', service.path,
        `${service.name} requires application.yml.`,
        'Add the service configuration or explicitly change the manifest requirement.')
    }
    if (!exists(`${service.path}/pom.xml`)) continue
    const artifacts = extractDependencyArtifacts(read(`${service.path}/pom.xml`))
    const actualInternal = artifacts.filter(artifact => internalNames.has(artifact))
    const allowed = new Set(service.allowedInternalDependencies ?? [])
    for (const dependency of actualInternal) {
      check(allowed.has(dependency), 'B005', `${service.path}/pom.xml`,
        `${service.name} has forbidden build-time dependency on ${dependency}.`,
        'Remove the service JAR dependency; use a shared contract plus Feign/Kafka at runtime.')
    }
  }

  const packageOwners = services.map(service => ({
    service,
    prefix: `${service.packagePrefix}.`,
  }))
  for (const file of walk('backend', file => file.endsWith('.java') && file.includes('/src/'))) {
    const owner = services.find(service => file.startsWith(`${service.path}/`))
    if (!owner) continue
    const imports = [...read(file).matchAll(/^import\s+(com\.sparrow\.[\w.]+);/gm)]
      .map(match => match[1])
    for (const imported of imports) {
      const target = packageOwners.find(entry =>
        imported === entry.service.packagePrefix || imported.startsWith(entry.prefix))?.service
      if (!target || target.name === owner.name) continue
      const allowed = new Set(owner.allowedInternalDependencies ?? [])
      check(allowed.has(target.name), 'B006', file,
        `${owner.name} directly imports package owned by ${target.name}: ${imported}.`,
        'Move a stable contract to `sparrow-common` or call the owning service through Feign/Kafka.')
    }
  }

  const expectedFrontend = manifest.frontend?.businessModules ?? []
  const modulesPath = manifest.frontend?.businessModulesPath
  if (modulesPath && exists(modulesPath)) {
    const actualFrontend = fs.readdirSync(absolute(modulesPath), { withFileTypes: true })
      .filter(entry => entry.isDirectory())
      .map(entry => entry.name)
      .sort()
    check(JSON.stringify(actualFrontend) === JSON.stringify([...expectedFrontend].sort()),
      'F001', modulesPath, 'Frontend business modules do not match the harness manifest.',
      'Update the manifest and architecture map with any intentional module addition/removal.')

    for (const moduleName of expectedFrontend) {
      const moduleRoot = `${modulesPath}/${moduleName}`
      for (const file of walk(moduleRoot, candidate => /\.(ts|tsx|vue|js|jsx)$/.test(candidate))) {
        const references = [...read(file).matchAll(/@\/modules\/([^/'"`)]+)/g)]
          .map(match => match[1])
        for (const target of references) {
          check(target === moduleName, 'F002', file,
            `Business module ${moduleName} imports business module ${target}.`,
            'Move shared code to `frontend/src/shared` or compose both modules from `src/app`.')
        }
      }
    }
  }

  for (const tool of manifest.pythonTools ?? []) {
    check(exists(tool.path), 'P001', tool.path, `Python tool ${tool.name} is missing.`,
      'Restore the tool or remove it from the manifest and architecture documentation together.')
    check(exists(`${tool.path}/README.md`), 'P002', tool.path,
      `Python tool ${tool.name} has no README.md.`,
      'Document its inputs, outputs, configuration and verification command.')
    check(exists(`${tool.path}/requirements.txt`), 'P003', tool.path,
      `Python tool ${tool.name} has no requirements.txt.`,
      'Declare its reproducible runtime dependencies or update the manifest if it is no longer Python.')
  }

  const tracked = trackedFiles()
  for (const protectedFile of manifest.protectedLocalFiles ?? []) {
    check(!tracked.includes(protectedFile), 'S001', protectedFile,
      'A protected local/secret-bearing file is tracked by Git.',
      'Remove it from the index without deleting the local copy, rotate exposed secrets, and use the example file.')
  }
  for (const file of tracked.filter(file => /(^|\/)\.env\./.test(file) && !file.endsWith('.env.example'))) {
    const content = exists(file) ? read(file) : ''
    const exposedSecret = content.match(/^\s*([A-Z0-9_]*(?:API_KEY|SECRET|TOKEN|PASSWORD)[A-Z0-9_]*)\s*=\s*(\S.+)\s*$/im)
    check(!exposedSecret, 'S002', file,
      `Tracked environment file contains a non-empty secret-like value for ${exposedSecret?.[1] ?? 'a protected key'}.`,
      'Remove the value from Git, rotate it if real, and load it from local or managed secret storage.')
  }

  const taskDirectory = manifest.taskState?.directory ?? '.harness/tasks'
  check(exists(manifest.taskState?.schema ?? ''), 'T000', manifest.taskState?.schema ?? '.harness/task.schema.json',
    'Task JSON schema is missing.', 'Restore the schema and keep it aligned with task validation rules.')
  const statuses = manifest.taskState?.statuses ?? []
  for (const file of walk(taskDirectory, candidate =>
    candidate.endsWith('.json') && !candidate.endsWith('/TEMPLATE.json') && !candidate.includes('/archive/'))) {
    validateTask(file, readJson(file), statuses)
  }
}

if (failures.length > 0) {
  console.error(`\nHarness guard failed with ${failures.length} violation(s):\n`)
  for (const item of failures) {
    console.error(`[${item.rule}] ${item.file}`)
    console.error(`  ${item.message}`)
    console.error(`  Fix: ${item.remediation}\n`)
  }
  process.exit(1)
}

console.log('Harness guard passed: repository map, service boundaries, module isolation, secrets and task state are valid.')
