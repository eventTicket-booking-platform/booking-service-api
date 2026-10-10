import http from 'k6/http';
import { Counter } from 'k6/metrics';

function required(name) {
  const value = (__ENV[name] || '').trim();
  if (!value) throw new Error(name + ' is required; see performance/README.md');
  return value;
}

export function positiveInteger(name, fallback) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isSafeInteger(value) || value < 1) throw new Error(name + ' must be a positive integer');
  return value;
}

export const config = {
  baseUrl: required('BASE_URL').replace(/\/+$/, ''),
  token: required('JWT_TOKEN'),
  eventId: positiveInteger('EVENT_ID'),
  ticketTypeId: positiveInteger('TICKET_TYPE_ID'),
  quantity: positiveInteger('QUANTITY', 1),
};
if (!/^https?:\/\//.test(config.baseUrl)) throw new Error('BASE_URL must start with http:// or https://');
if (/^Bearer\s/i.test(config.token)) throw new Error('JWT_TOKEN must exclude the Bearer prefix');
if (config.quantity >= 2147483647) throw new Error('QUANTITY must allow quantity + 1 within a Java Integer');

export const url = config.baseUrl + '/booking-service/api/v1/bookings';
export const body = JSON.stringify({
  eventId: config.eventId,
  ticketSelections: [{ ticketTypeId: config.ticketTypeId, quantity: config.quantity }],
  paymentMethod: 'CARD',
});
export const metrics = {
  attempts: new Counter('booking_attempts'),
  confirmed: new Counter('booking_confirmed_responses'),
  stockRejected: new Counter('booking_stock_rejections'),
  unexpected: new Counter('booking_unexpected_responses'),
  replayed: new Counter('booking_matching_replays'),
  conflicts: new Counter('booking_body_conflicts'),
};
export const expected = {
  success: http.expectedStatuses(201),
  inventory: http.expectedStatuses(201, 409),
  conflict: http.expectedStatuses(409),
};

export function startRun(scenario, details = {}) {
  const runId = __ENV.RUN_ID || String(Date.now()) + '-' + Math.floor(Math.random() * 1000000000);
  if (!/^[A-Za-z0-9-]{1,50}$/.test(runId)) throw new Error('RUN_ID must be 1-50 letters, digits or hyphens');
  Object.values(metrics).forEach(metric => metric.add(0));
  console.log(JSON.stringify({ runId, scenario, eventId: config.eventId,
    ticketTypeId: config.ticketTypeId, quantity: config.quantity, ...details }));
  return { runId };
}

export function params(key, stage, responseCallback) {
  return {
    headers: {
      Authorization: 'Bearer ' + config.token,
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
      'X-Correlation-ID': key,
    },
    tags: { name: 'create-booking', stage },
    timeout: '30s',
    redirects: 0,
    responseCallback,
  };
}

export function json(response) {
  try { return response.json(); } catch (_) { return null; }
}

export function isConfirmed(response, result) {
  return response.status === 201 && result !== null && Number.isSafeInteger(result.bookingId)
    && result.bookingId > 0 && typeof result.bookingReference === 'string' && !!result.bookingReference
    && result.eventId === config.eventId && result.status === 'CONFIRMED'
    && result.payment && result.payment.status === 'SUCCESS'
    && Array.isArray(result.items) && result.items.length === 1
    && result.items[0].ticketTypeId === config.ticketTypeId && result.items[0].quantity === config.quantity;
}

export function evidence(key, response, result) {
  // Identifiers/outcomes only: no token, credentials or full personal response data.
  console.log(JSON.stringify({ key, httpStatus: response.status,
    bookingId: result && result.bookingId, bookingStatus: result && result.status }));
}

export function summarize(data, scenario, details = {}) {
  const count = name => data.metrics[name] ? data.metrics[name].values.count : 0;
  const failedThresholds = Object.entries(data.metrics).flatMap(([name, metric]) =>
    Object.entries(metric.thresholds || {}).filter(([, result]) => !result.ok)
      .map(([threshold]) => name + ': ' + threshold));
  const result = {
    scenario, runId: data.setup_data && data.setup_data.runId,
    eventId: config.eventId, ticketTypeId: config.ticketTypeId, quantity: config.quantity, ...details,
    attempts: count('booking_attempts'),
    confirmedResponses: count('booking_confirmed_responses'),
    stockRejections: count('booking_stock_rejections'),
    matchingReplays: count('booking_matching_replays'),
    changedBodyConflicts: count('booking_body_conflicts'),
    unexpectedResponses: count('booking_unexpected_responses'),
    checks: data.metrics.checks ? data.metrics.checks.values : {},
    httpDurationMs: data.metrics.http_req_duration ? data.metrics.http_req_duration.values : {},
    failedThresholds,
    note: 'Confirmed responses include replays. Verify unique bookings and inventory in the databases.',
  };
  const file = __ENV.SUMMARY_FILE || 'booking-' + scenario + '-' + (result.runId || 'unknown') + '-summary.json';
  return {
    stdout: JSON.stringify(result, null, 2) + '\n',
    [file]: JSON.stringify({ result, metrics: data.metrics }, null, 2),
  };
}
