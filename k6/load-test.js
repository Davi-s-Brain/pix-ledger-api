// Teste de carga do pix-ledger-api (k6)
//
// Como rodar (app rodando em :8081, prometheus+grafana no ar):
//   docker run --network host -i grafana/k6 run - < k6/load-test.js
//
// Variáveis (opcionais, via -e):
//   DURATION=30s        duração dos cenários
//   TRANSFERS_TPS=200   transferências/s alvo (constant-arrival-rate)
//   ENTRIES_TPS=100     lançamentos/s alvo
//   READ_VUS=20         VUs de leitura (cache)
//   PAIRS=10            pares de contas usados pelas transferências
//
// O que cada cenário prova:
//   reads      -> caminho de leitura com cache Redis (hit = rápido)
//   entries    -> lock otimista + retry sob contenção (409 = retry exaurido, esperado)
//   transfers  -> lock pessimista: serializa POR PAR de contas; com PAIRS pares,
//                 o throughput máximo = PAIRS / tempo de lock. Ajuste PAIRS.
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8081';
const DURATION = __ENV.DURATION || '30s';
const TRANSFERS_TPS = Number(__ENV.TRANSFERS_TPS || 200);
const ENTRIES_TPS = Number(__ENV.ENTRIES_TPS || 100);
const READ_VUS = Number(__ENV.READ_VUS || 20);
const PAIRS = Number(__ENV.PAIRS || 10);

export const options = {
  scenarios: {
    reads: {
      executor: 'constant-vus',
      exec: 'reads',
      vus: READ_VUS,
      duration: DURATION,
    },
    entries: {
      executor: 'constant-arrival-rate',
      exec: 'entries',
      rate: ENTRIES_TPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 30,
      maxVUs: 100,
    },
    transfers: {
      executor: 'constant-arrival-rate',
      exec: 'transfers',
      rate: TRANSFERS_TPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    // erros de verdade (5xx/timeout) — 4xx esperados de negócio NÃO contam aqui
    'http_req_failed{scenario:reads}': ['rate<0.01'],
    'http_req_failed{scenario:transfers}': ['rate<0.01'],
    'checks{scenario:entries}': ['rate>0.95'],
    'http_req_duration{scenario:reads}': ['p(95)<200'],
    // teto generoso p/ transfer: lock serializa, fila aparece no p95 real
    'http_req_duration{scenario:transfers}': ['p(95)<2000'],
  },
};

const auth = (token) => ({ Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' });

function login() {
  const res = http.post(`${BASE}/api/v1/auth/login`,
    JSON.stringify({ name: 'admin', password: 'admin123' }),
    { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 200) throw new Error('login falhou: ' + res.status);
  return res.json().accessToken;
}

function createAccount(token, name) {
  const res = http.post(`${BASE}/api/v1/accounts`, JSON.stringify({ ownerName: name }), { headers: auth(token) });
  if (res.status !== 201) throw new Error('criar conta falhou: ' + res.status + ' ' + res.body);
  return res.json().id;
}

function credit(token, id, amount) {
  const res = http.post(`${BASE}/api/v1/accounts/${id}/entries`,
    JSON.stringify({ entryType: 'CREDIT', amount }), { headers: auth(token) });
  if (res.status !== 201) throw new Error('credit falhou: ' + res.status + ' ' + res.body);
}

// cria o dataset: PAIRS pares A/B (ambos fundidos -> loop A->B / B->A não drena saldo)
// + 1 conta "sink" fundida para o cenário de entries
export function setup() {
  const token = login();
  const pairs = [];
  for (let i = 0; i < PAIRS; i++) {
    const a = createAccount(token, `load-a-${i}`);
    const b = createAccount(token, `load-b-${i}`);
    credit(token, a, 1000000);
    credit(token, b, 1000000);
    pairs.push({ a, b });
  }
  const entrySink = createAccount(token, 'entry-sink');
  credit(token, entrySink, 100000000);
  return { token, pairs, entrySink };
}

export function reads(data) {
  const account = data.pairs[(__VU + __ITER) % data.pairs.length];
  const headers = auth(data.token);

  const acc = http.get(`${BASE}/api/v1/accounts/${account.a}`, { headers });
  check(acc, { 'GET account 200': (r) => r.status === 200 });

  const ledger = http.get(`${BASE}/api/v1/accounts/${account.a}/ledger?page=0&size=10`, { headers });
  check(ledger, { 'GET ledger 200': (r) => r.status === 200 });

  sleep(0.05);
}

// débito de 1.00 no mesmo sink: contenção máxima no lock otimista (@Version + retry)
export function entries(data) {
  const res = http.post(`${BASE}/api/v1/accounts/${data.entrySink}/entries`,
    JSON.stringify({ entryType: 'DEBIT', amount: 1.00 }), { headers: auth(data.token) });
  // 201 = sucesso; 409 = retry exaurido sob contenção (esperado e observável no Grafana)
  check(res, { 'entry 201 ou 409 (sem 5xx)': (r) => r.status === 201 || r.status === 409 });
}

// alterna direção por iteração: par mantém saldo estável e sustenta carga
export function transfers(data) {
  const pair = data.pairs[(__VU + __ITER) % data.pairs.length];
  const forward = __ITER % 2 === 0;
  const source = forward ? pair.a : pair.b;
  const destination = forward ? pair.b : pair.a;

  const res = http.post(`${BASE}/api/v1/transfers`,
    JSON.stringify({ sourceAccountId: source, destinationAccountId: destination, amount: 10.00 }),
    { headers: auth(data.token) });
  check(res, { 'transfer 201': (r) => r.status === 201 });
}