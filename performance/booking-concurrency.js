import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { config, positiveInteger, url, body, metrics, expected, startRun, params,
  json, isConfirmed, evidence, summarize } from './booking-common.js';

const initialStock = positiveInteger('INITIAL_STOCK');
const attempts = positiveInteger('ATTEMPTS', 20);
const vus = positiveInteger('VUS', 10);
const capacity = Math.floor(initialStock / config.quantity);
if (capacity < 1 || attempts <= capacity) {
  throw new Error('Use at least one bookable quantity and more attempts than initial stock can satisfy');
}
if (vus < 2 || vus > attempts) throw new Error('VUS must be between 2 and ATTEMPTS');

export const options = {
  scenarios: {
    inventory: { executor: 'shared-iterations', vus, iterations: attempts, maxDuration: '2m' },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    booking_attempts: ['count==' + attempts],
    booking_confirmed_responses: ['count>0', 'count<=' + capacity],
    booking_stock_rejections: ['count>0'],
    booking_unexpected_responses: ['count==0'],
  },
};

export function setup() {
  return startRun('inventory', { initialStock, attempts, vus });
}

export default function (data) {
  const key = 'k6-' + data.runId + '-inventory-' + exec.scenario.iterationInTest;
  metrics.attempts.add(1);
  const response = http.post(url, body, params(key, 'inventory', expected.inventory));
  const result = json(response);
  const success = isConfirmed(response, result);
  // Do not accept an unrelated 409 (invalid event, reused key, etc.) as stock exhaustion.
  const soldOut = response.status === 409 && result && typeof result.message === 'string'
    && result.message.includes('Not enough tickets available for ticket type: ' + config.ticketTypeId);
  metrics.confirmed.add(success ? 1 : 0);
  metrics.stockRejected.add(soldOut ? 1 : 0);
  metrics.unexpected.add(success || soldOut ? 0 : 1);
  check(response, { 'inventory returns confirmed 201 or stock-exhausted 409': () => success || soldOut });
  evidence(key, response, result);
}

export function handleSummary(data) {
  return summarize(data, 'inventory', { initialStock, attemptsRequested: attempts, vus });
}
