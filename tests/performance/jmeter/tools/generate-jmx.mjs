#!/usr/bin/env node
/**
 * Generates the checked-in JMeter plans from one set of helpers.
 * Run: node tools/generate-jmx.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const outDir = path.resolve(here, '..');

const esc = (value) => String(value)
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;');

const arg = (name, value) => `<elementProp name="${esc(name)}" elementType="Argument">
  <stringProp name="Argument.name">${esc(name)}</stringProp>
  <stringProp name="Argument.value">${esc(value)}</stringProp>
  <stringProp name="Argument.metadata">=</stringProp>
</elementProp>`;

const udv = (values) => `<elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
  <collectionProp name="Arguments.arguments">
    ${values.map(([name, value]) => arg(name, value)).join('\n    ')}
  </collectionProp>
</elementProp>`;

const headerManager = (name, headers) => `<HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="${esc(name)}" enabled="true">
  <collectionProp name="HeaderManager.headers">
    ${headers.map(([header, value]) => `<elementProp name="" elementType="Header">
      <stringProp name="Header.name">${esc(header)}</stringProp>
      <stringProp name="Header.value">${esc(value)}</stringProp>
    </elementProp>`).join('\n    ')}
  </collectionProp>
</HeaderManager>
<hashTree/>`;

const httpDefaults = () => `<ConfigTestElement guiclass="HttpDefaultsGui" testclass="ConfigTestElement" testname="HTTP Defaults" enabled="true">
  <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
    <collectionProp name="Arguments.arguments"/>
  </elementProp>
  <stringProp name="HTTPSampler.domain">\${SERVER}</stringProp>
  <stringProp name="HTTPSampler.port">\${PORT}</stringProp>
  <stringProp name="HTTPSampler.protocol">\${PROTOCOL}</stringProp>
  <stringProp name="HTTPSampler.contentEncoding">UTF-8</stringProp>
  <stringProp name="HTTPSampler.path"></stringProp>
  <stringProp name="HTTPSampler.concurrentPool">6</stringProp>
  <stringProp name="HTTPSampler.connect_timeout">5000</stringProp>
  <stringProp name="HTTPSampler.response_timeout">15000</stringProp>
</ConfigTestElement>
<hashTree/>`;

const codeAssertion = (pattern = '^2\\d\\d$', label = 'HTTP 2xx') => `<ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="${esc(label)}" enabled="true">
  <collectionProp name="Asserion.test_strings">
    <stringProp name="${esc(pattern)}">${esc(pattern)}</stringProp>
  </collectionProp>
  <stringProp name="Assertion.custom_message">${esc(label)}</stringProp>
  <stringProp name="Assertion.test_field">Assertion.response_code</stringProp>
  <boolProp name="Assertion.assume_success">false</boolProp>
  <intProp name="Assertion.test_type">1</intProp>
</ResponseAssertion>
<hashTree/>`;

const statusAssertion = (status) => codeAssertion(`^${status}$`, `HTTP ${status}`);

const bodyContainsAssertion = (needle, label) => `<ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="${esc(label)}" enabled="true">
  <collectionProp name="Asserion.test_strings">
    <stringProp name="${esc(needle)}">${esc(needle)}</stringProp>
  </collectionProp>
  <stringProp name="Assertion.custom_message">${esc(label)}</stringProp>
  <stringProp name="Assertion.test_field">Assertion.response_data</stringProp>
  <boolProp name="Assertion.assume_success">false</boolProp>
  <intProp name="Assertion.test_type">16</intProp>
</ResponseAssertion>
<hashTree/>`;

const jsonAssertion = (jsonPath, expected, label = `${jsonPath} == ${expected}`) => `<JSONPathAssertion guiclass="JSONPathAssertionGui" testclass="JSONPathAssertion" testname="${esc(label)}" enabled="true">
  <stringProp name="JSON_PATH">${esc(jsonPath)}</stringProp>
  <stringProp name="EXPECTED_VALUE">${esc(expected)}</stringProp>
  <boolProp name="JSONVALIDATION">true</boolProp>
  <boolProp name="EXPECT_NULL">false</boolProp>
  <boolProp name="INVERT">false</boolProp>
  <boolProp name="ISREGEX">false</boolProp>
</JSONPathAssertion>
<hashTree/>`;

const http = ({
  name, method = 'GET', path: requestPath, body = null, timeout = 15000,
  server = null, port = null, protocol = null, assertions = [],
}) => {
  const argumentsXml = body === null
    ? '<collectionProp name="Arguments.arguments"/>'
    : `<collectionProp name="Arguments.arguments">
      <elementProp name="" elementType="HTTPArgument">
        <boolProp name="HTTPArgument.always_encode">false</boolProp>
        <stringProp name="Argument.value">${esc(body)}</stringProp>
        <stringProp name="Argument.metadata">=</stringProp>
      </elementProp>
    </collectionProp>`;
  return `<HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="${esc(name)}" enabled="true">
  <boolProp name="HTTPSampler.postBodyRaw">${body !== null}</boolProp>
  <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
    ${argumentsXml}
  </elementProp>
  <stringProp name="HTTPSampler.domain">${server === null ? '' : esc(server)}</stringProp>
  <stringProp name="HTTPSampler.port">${port === null ? '' : esc(port)}</stringProp>
  <stringProp name="HTTPSampler.protocol">${protocol === null ? '' : esc(protocol)}</stringProp>
  <stringProp name="HTTPSampler.contentEncoding">UTF-8</stringProp>
  <stringProp name="HTTPSampler.path">${esc(requestPath)}</stringProp>
  <stringProp name="HTTPSampler.method">${method}</stringProp>
  <boolProp name="HTTPSampler.follow_redirects">true</boolProp>
  <boolProp name="HTTPSampler.auto_redirects">false</boolProp>
  <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
  <boolProp name="HTTPSampler.DO_MULTIPART_POST">false</boolProp>
  <stringProp name="HTTPSampler.embedded_url_re"></stringProp>
  <stringProp name="HTTPSampler.connect_timeout">5000</stringProp>
  <stringProp name="HTTPSampler.response_timeout">${timeout}</stringProp>
</HTTPSamplerProxy>
<hashTree>
  ${assertions.join('\n  ')}
</hashTree>`;
};

// Use a plain controller: TransactionController would add a second sample for
// the same HTTP requests and double-count throughput and percentiles.
const transaction = (name, children) => `<GenericController guiclass="LogicControllerGui" testclass="GenericController" testname="${esc(name)}" enabled="true"/>
<hashTree>
  ${children.join('\n  ')}
</hashTree>`;

const simpleController = (name, children) => `<GenericController guiclass="LogicControllerGui" testclass="GenericController" testname="${esc(name)}" enabled="true"/>
<hashTree>
  ${children.join('\n  ')}
</hashTree>`;

const randomController = (name, children) => `<RandomController guiclass="RandomControlGui" testclass="RandomController" testname="${esc(name)}" enabled="true"/>
<hashTree>
  ${children.join('\n  ')}
</hashTree>`;

const ifController = (name, condition, children) => `<IfController guiclass="IfControllerPanel" testclass="IfController" testname="${esc(name)}" enabled="true">
  <stringProp name="IfController.condition">${esc(condition)}</stringProp>
  <boolProp name="IfController.evaluateAll">false</boolProp>
  <boolProp name="IfController.useExpression">true</boolProp>
</IfController>
<hashTree>
  ${children.join('\n  ')}
</hashTree>`;

const threadGroup = ({
  name, threads, ramp, duration, delay = 0, loops = null, children,
}) => `<ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="${esc(name)}" enabled="true">
  <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
  <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" testname="Loop Controller" enabled="true">
    <boolProp name="LoopController.continue_forever">${loops === null}</boolProp>
    <stringProp name="LoopController.loops">${loops === null ? '-1' : loops}</stringProp>
  </elementProp>
  <stringProp name="ThreadGroup.num_threads">${threads}</stringProp>
  <stringProp name="ThreadGroup.ramp_time">${ramp}</stringProp>
  <boolProp name="ThreadGroup.scheduler">${loops === null}</boolProp>
  <stringProp name="ThreadGroup.duration">${loops === null ? duration : 0}</stringProp>
  <stringProp name="ThreadGroup.delay">${delay}</stringProp>
  <boolProp name="ThreadGroup.same_user_on_next_iteration">true</boolProp>
</ThreadGroup>
<hashTree>
  ${children.join('\n  ')}
</hashTree>`;

const testPlan = (name, comment, threadGroups) => `<TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="${esc(name)}" enabled="true">
  <stringProp name="TestPlan.comments">${esc(comment)}</stringProp>
  <boolProp name="TestPlan.functional_mode">false</boolProp>
  <boolProp name="TestPlan.tearDown_on_shutdown">true</boolProp>
  <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
  ${udv([
    ['SERVER', '${__P(server,127.0.0.1)}'],
    ['PORT', '${__P(port,8090)}'],
    ['PROTOCOL', '${__P(protocol,http)}'],
    ['MGMT_SERVER', '${__P(mgmtServer,127.0.0.1)}'],
    ['MGMT_PORT', '${__P(mgmtPort,8091)}'],
    ['MGMT_PROTOCOL', '${__P(mgmtProtocol,http)}'],
    ['TOKEN', '${__P(token,NOT_SET)}'],
    ['PROJECT_ID', '${__P(projectId,NOT_SET)}'],
    ['TASK_ID', '${__P(taskId,NOT_SET)}'],
    ['SESSION_ID', '${__P(sessionId,NOT_SET)}'],
    ['AI_SERVICE_TOKEN', '${__P(aiServiceToken,NOT_SET)}'],
    ['THREADS', '${__P(threads,10)}'],
    ['RAMP', '${__P(ramp,10)}'],
    ['DURATION', '${__P(duration,60)}'],
  ])}
  <stringProp name="TestPlan.user_define_classpath"></stringProp>
</TestPlan>
<hashTree>
  ${httpDefaults()}
  ${threadGroups.join('\n  ')}
</hashTree>`;

const jmx = (name, comment, threadGroups) => `<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
  <hashTree>
    ${testPlan(name, comment, threadGroups)}
  </hashTree>
</jmeterTestPlan>
`;

const bearer = () => headerManager('Bearer access token', [
  ['Authorization', 'Bearer ${TOKEN}'],
  ['Content-Type', 'application/json'],
  ['Accept', 'application/json'],
]);

const sseBearer = () => headerManager('Bearer access token (SSE)', [
  ['Authorization', 'Bearer ${TOKEN}'],
  ['Content-Type', 'application/json'],
  ['Accept', 'text/event-stream'],
]);

const appRead = (name, method, requestPath, { status = 200, json = true, body = null, timeout = 15000 } = {}) => transaction(name, [
  bearer(),
  http({
    name: `${method} ${requestPath}`,
    method,
    path: requestPath,
    body,
    timeout,
    assertions: [
      statusAssertion(status),
      ...(json ? [assertionJsonCode()] : []),
    ],
  }),
]);

const assertionJsonCode = () => jsonAssertion('$.code', 'SUCCESS', 'ApiResponse code=SUCCESS');

const readBranches = () => [
  appRead('Read: home aggregate', 'GET', '/api/v1/home'),
  appRead('Read: project list', 'GET', '/api/v1/projects'),
  appRead('Read: project detail', 'GET', '/api/v1/projects/${PROJECT_ID}'),
  appRead('Read: dashboard', 'GET', '/api/v1/projects/${PROJECT_ID}/dashboard'),
  appRead('Read: members', 'GET', '/api/v1/projects/${PROJECT_ID}/members'),
  appRead('Read: task list', 'GET', '/api/v1/projects/${PROJECT_ID}/tasks'),
  appRead('Read: task detail', 'GET', '/api/v1/projects/${PROJECT_ID}/tasks/${TASK_ID}'),
  appRead('Read: milestones', 'GET', '/api/v1/projects/${PROJECT_ID}/milestones'),
  appRead('Read: gantt', 'GET', '/api/v1/projects/${PROJECT_ID}/visualization/gantt'),
  appRead('Read: member load', 'GET', '/api/v1/projects/${PROJECT_ID}/visualization/member-load'),
  appRead('Read: audit logs', 'GET', '/api/v1/projects/${PROJECT_ID}/audit-logs?page=1&size=20'),
  appRead('Read: unread notifications', 'GET', '/api/v1/notifications/unread-count'),
];

const writeBranch = () => transaction('Write: task lifecycle (create/update/delete)', [
  bearer(),
  http({
    name: 'POST create task',
    method: 'POST',
    path: '/api/v1/projects/${PROJECT_ID}/tasks',
    body: '{"title":"perf-write-${__threadNum()}-${__time()}-${__counter(FALSE,)}","description":"jmeter mixed write; deleted in the same iteration","status":"TODO","priority":"MEDIUM","estimateHours":4}',
    assertions: [
      statusAssertion(201),
      assertionJsonCode(),
      `<JMESPathExtractor guiclass="JMESPathExtractorGui" testclass="JMESPathExtractor" testname="Extract created task id" enabled="true">
        <stringProp name="JMESExtractor.jmesPathExpr">data.id</stringProp>
        <stringProp name="JMESExtractor.referenceName">createdTaskId</stringProp>
        <stringProp name="JMESExtractor.defaultValue">NOT_FOUND</stringProp>
        <stringProp name="JMESExtractor.matchNumber"></stringProp>
      </JMESPathExtractor>
      <hashTree/>`,
      `<JMESPathExtractor guiclass="JMESPathExtractorGui" testclass="JMESPathExtractor" testname="Extract created task version" enabled="true">
        <stringProp name="JMESExtractor.jmesPathExpr">data.version</stringProp>
        <stringProp name="JMESExtractor.referenceName">createdTaskVersion</stringProp>
        <stringProp name="JMESExtractor.defaultValue">0</stringProp>
        <stringProp name="JMESExtractor.matchNumber"></stringProp>
      </JMESPathExtractor>
      <hashTree/>`,
    ],
  }),
  ifController('Only update/delete when create succeeded', '${JMeterThread.last_sample_ok}', [
    http({
      name: 'PATCH update task',
      method: 'PATCH',
      path: '/api/v1/projects/${PROJECT_ID}/tasks/${createdTaskId}',
      body: '{"title":"perf-write-updated-${__threadNum()}-${__time()}","status":"IN_PROGRESS","priority":"HIGH","version":${createdTaskVersion}}',
      assertions: [statusAssertion(200), assertionJsonCode()],
    }),
    http({
      name: 'DELETE task',
      method: 'DELETE',
      path: '/api/v1/projects/${PROJECT_ID}/tasks/${createdTaskId}',
      assertions: [statusAssertion(204)],
    }),
  ]),
]);

const readMix = (name) => randomController(name, readBranches());
const mixedMix = (name) => randomController(name, [...readBranches(), writeBranch()]);

const plans = {};

plans['smoke.jmx'] = jmx(
  'AI Collab / smoke',
  'One sequential pass over health and core read paths. Fails fast on auth, seed data or contract drift.',
  [threadGroup({
    name: 'Smoke users',
    threads: 1,
    ramp: 1,
    duration: 0,
    loops: 1,
    children: [
      http({
        name: 'GET /actuator/health',
        method: 'GET',
        path: '/actuator/health',
        server: '${MGMT_SERVER}',
        port: '${MGMT_PORT}',
        protocol: '${MGMT_PROTOCOL}',
        assertions: [statusAssertion(200), jsonAssertion('$.status', 'UP', 'health status=UP')],
      }),
      simpleController('Core read path', [
        bearer(),
        ...readBranches(),
        appRead('Read: knowledge sessions', 'GET', '/api/v1/projects/${PROJECT_ID}/knowledge/sessions'),
      ]),
    ],
  })],
);

plans['read-baseline.jmx'] = jmx(
  'AI Collab / read baseline',
  'Read-only baseline across project, task, dashboard, visualization and notification paths.',
  [threadGroup({
    name: 'Read baseline users',
    threads: '${THREADS}',
    ramp: '${RAMP}',
    duration: '${DURATION}',
    children: [readMix('Read path mix')],
  })],
);

plans['mixed-baseline.jmx'] = jmx(
  'AI Collab / mixed baseline',
  'Twelve read branches plus one three-request task write lifecycle (80/20 at request level).',
  [threadGroup({
    name: 'Mixed baseline users',
    threads: '${THREADS}',
    ramp: '${RAMP}',
    duration: '${DURATION}',
    children: [mixedMix('Read/write mix')],
  })],
);

plans['spike.jmx'] = jmx(
  'AI Collab / spike',
  'Warm 5 users, then add 35 users in one second; same read mix as baseline.',
  [
    threadGroup({
      name: 'Warm-up users',
      threads: 5,
      ramp: 5,
      duration: 30,
      children: [readMix('Warm-up read mix')],
    }),
    threadGroup({
      name: 'Spike users',
      threads: 35,
      ramp: 1,
      duration: 30,
      delay: 10,
      children: [readMix('Spike read mix')],
    }),
  ],
);

plans['soak.jmx'] = jmx(
  'AI Collab / soak',
  'Ten-minute mixed run with a stable write lifecycle to expose leaks and pool starvation.',
  [threadGroup({
    name: 'Soak users',
    threads: '${THREADS}',
    ramp: '${RAMP}',
    duration: '${DURATION}',
    children: [mixedMix('Soak read/write mix')],
  })],
);

plans['ai-stub.jmx'] = jmx(
  'AI Collab / ai stub',
  'Internal Java model gateway to a local 200 ms OpenAI-compatible stub. No real provider is called.',
  [threadGroup({
    name: 'AI stub callers',
    threads: '${THREADS}',
    ramp: '${RAMP}',
    duration: '${DURATION}',
    children: [
      headerManager('Internal service token', [
        ['Authorization', 'Bearer ${AI_SERVICE_TOKEN}'],
        ['Content-Type', 'application/json'],
      ]),
      http({
        name: 'POST /internal/ai/v1/model-calls',
        method: 'POST',
        path: '/internal/ai/v1/model-calls',
        timeout: 5000,
        body: '{"schemaVersion":1,"purpose":"KNOWLEDGE_CHAT","systemPrompt":"perf stub system prompt","userPrompt":"perf stub user prompt","outputFormat":"PROMPT_JSON","deadlineMs":3000,"callId":"${__UUID()}"}',
        assertions: [
          statusAssertion(200),
          jsonAssertion('$.schemaVersion', '1', 'AI response schemaVersion=1'),
          bodyContainsAssertion('stub-ok', 'AI stub content present'),
          `<JSR223PostProcessor guiclass="TestBeanGUI" testclass="JSR223PostProcessor" testname="Compute gateway overhead" enabled="true">
            <stringProp name="scriptLanguage">groovy</stringProp>
            <stringProp name="parameters"></stringProp>
            <stringProp name="filename"></stringProp>
            <stringProp name="cacheKey">true</stringProp>
            <stringProp name="script">import groovy.json.JsonSlurper
try {
  def body = prev.getResponseDataAsString()
  def json = new JsonSlurper().parseText(body)
  def provider = json.latencyMs instanceof Number ? json.latencyMs.longValue() : 0L
  vars.put('providerLatencyMs', provider.toString())
  vars.put('overheadMs', Math.max(0L, prev.getTime() - provider).toString())
} catch (Exception ignored) {
  vars.put('providerLatencyMs', '')
  vars.put('overheadMs', '')
}
            </stringProp>
          </JSR223PostProcessor>
          <hashTree/>`,
        ],
      }),
    ],
  })],
);

plans['sse-smoke.jmx'] = jmx(
  'AI Collab / sse smoke',
  'Knowledge SSE protocol smoke on a seeded session with no documents. No model call is made.',
  [threadGroup({
    name: 'SSE smoke users',
    threads: 2,
    ramp: 1,
    duration: 0,
    loops: 5,
    children: [
      sseBearer(),
      http({
        name: 'POST knowledge questions/stream',
        method: 'POST',
        path: '/api/v1/projects/${PROJECT_ID}/knowledge/sessions/${SESSION_ID}/questions/stream',
        timeout: 30000,
        body: '{"question":"perf sse smoke ${__threadNum()}-${__time()}"}',
        assertions: [
          statusAssertion(200),
          `<ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="SSE content type" enabled="true">
            <collectionProp name="Asserion.test_strings">
              <stringProp name="text/event-stream">text/event-stream</stringProp>
            </collectionProp>
            <stringProp name="Assertion.custom_message">SSE content type</stringProp>
            <stringProp name="Assertion.test_field">Assertion.response_headers</stringProp>
            <boolProp name="Assertion.assume_success">false</boolProp>
            <intProp name="Assertion.test_type">16</intProp>
          </ResponseAssertion>
          <hashTree/>`,
          bodyContainsAssertion('event:done', 'SSE done event'),
          bodyContainsAssertion('"type":"done"', 'SSE done payload'),
        ],
      }),
    ],
  })],
);

plans['project-detail-followup.jmx'] = jmx(
  'AI Collab / project detail follow-up',
  'Single project-detail endpoint, loop-count driven; warm-up plus three 10k-sample rounds.',
  [threadGroup({
    name: 'Project detail follow-up users',
    threads: '${THREADS}',
    ramp: '${RAMP}',
    duration: 0,
    loops: '${__P(loops,500)}',
    children: [
      bearer(),
      http({
        name: 'Follow-up GET project detail',
        method: 'GET',
        path: '/api/v1/projects/${PROJECT_ID}',
        assertions: [statusAssertion(200), assertionJsonCode()],
      }),
    ],
  })],
);

for (const [file, content] of Object.entries(plans)) {
  fs.writeFileSync(path.join(outDir, file), content, 'utf8');
  console.log(`${file} ${content.length} bytes`);
}
