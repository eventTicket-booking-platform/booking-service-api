import http from 'k6/http';
import { check } from 'k6';
import { positiveInteger, url, body, metrics, expected, startRun, params,
  json, isConfirmed, evidence, summarize } from './booking-common.js';

const replays = positiveInteger('REPLAYS', 5);
export const options = {
  scenarios: { idempotency: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '2m' } },
  batch: replays,
  batchPerHost: replays,
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    booking_attempts: ['count==' + (replays + 2)],
    booking_matching_replays: ['count==' + replays],
    booking_body_conflicts: ['count==1'],
    booking_unexpected_responses: ['count==0'],
  },
};

export function setup() {
  return startRun('idempotency', { replays });
}

export default function (data) {
  const key = 'k6-' + data.runId + '-idempotency';
  metrics.attempts.add(1);
  const first = http.post(url, body, params(key, 'first', expected.success));
  const original = json(first);
  const created = isConfirmed(first, original);
  check(first, { 'initial request creates a confirmed booking': () => created });
  metrics.confirmed.add(created ? 1 : 0);
  metrics.unexpected.add(created ? 0 : 1);
  evidence(key, first, original);
  if (!created) return; // Seed failed: checks and attempt/replay thresholds will fail.

  // Race completed replays; this does not claim to test concurrent first-time key acquisition.
  const responses = http.batch(Array.from({ length: replays }, () => ({
    method: 'POST', url, body, params: params(key, 'replay', expected.success),
  })));
  responses.forEach(response => {
    metrics.attempts.add(1);
    const result = json(response);
    const match = isConfirmed(response, result) && result.bookingId === original.bookingId
      && result.bookingReference === original.bookingReference && result.totalAmount === original.totalAmount;
    check(response, { 'same user/key/body returns the same confirmed booking': () => match });
    metrics.confirmed.add(isConfirmed(response, result) ? 1 : 0);
    metrics.replayed.add(match ? 1 : 0);
    metrics.unexpected.add(match ? 0 : 1);
    evidence(key, response, result);
  });

  const changed = JSON.parse(body);
  changed.ticketSelections[0].quantity += 1;
  metrics.attempts.add(1);
  const response = http.post(url, JSON.stringify(changed), params(key, 'changed-body', expected.conflict));
  const result = json(response);
  const conflict = response.status === 409 && result
    && result.message === 'Idempotency key was already used with a different request';
  check(response, { 'same key with changed quantity returns request-conflict 409': () => conflict });
  metrics.conflicts.add(conflict ? 1 : 0);
  metrics.unexpected.add(conflict ? 0 : 1);
  evidence(key, response, result);
}

export function handleSummary(data) {
  return summarize(data, 'idempotency', { replaysRequested: replays });
}
