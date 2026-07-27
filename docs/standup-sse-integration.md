# Stand-up Pipeline — SSE Integration Guide

## 1. Trigger the pipeline

```http
POST /api/v1/admin/cohorts/{cohortId}/standup
Authorization: Bearer <jwt>
```

Response (`202 Accepted`):

```json
{
  "success": true,
  "message": "Stand-up job started.",
  "data": {
    "id": "job-uuid",
    "cohortId": "cohort-uuid",
    "status": "RUNNING",
    "startedAt": "2026-07-27T10:00:00Z"
  }
}
```

---

## 2. Open the SSE stream

```js
const token = getJwt(); // your stored JWT
const url = `/api/v1/admin/cohorts/${cohortId}/standup/stream?token=${token}`;
const es = new EventSource(url);
```

Browser `EventSource` handles reconnection automatically using `Last-Event-ID` — if the connection drops mid-pipeline it reconnects and replays any events it missed.

> **Why `?token=`?** Browser `EventSource` cannot send custom headers, so the JWT is passed as a query parameter. The server reads it via the `JwtAuthenticationFilter` query-param fallback.

---

## 3. Event sequence

Events arrive one at a time as each gate completes.

### Gate 1 — SharePoint link validation

**Pass:**
```
event: gate.passed
id: 0
data: {"gate":1,"driveId":"b!abc...","itemId":"01ABC..."}
```

**Fail (pipeline stops here):**
```
event: gate.failed
id: 0
data: {"gate":1,"errors":["G1-NOT-FOUND: SharePoint item not accessible"]}
```

---

### Gate 2 — Folder structure check

**Pass:**
```
event: gate.passed
id: 1
data: {"gate":2,"referenceFolderItemId":"01DEF..."}
```

**Fail:**
```
event: gate.failed
id: 1
data: {"gate":2,"errors":["G2-MISSING-REF: Required folder 'Reference Data' not found. Found: [lab scores, other folder]"]}
```

---

### Gate 3 — Reference file validation

**Pass:**
```
event: gate.passed
id: 2
data: {"gate":3,"specs":4,"modules":12,"labs":36,"learners":120,"quizReferencePresent":true}
```

**Fail:**
```
event: gate.failed
id: 2
data: {"gate":3,"errors":["G3-SPEC-MISMATCH: row 14 — trainee spec 'Backend' not found in Specializations.xlsx"]}
```

---

### Final — pipeline done (always last)

**All gates passed:**
```
event: pipeline.done
id: 3
data: {"status":"COMPLETED","specs":4,"modules":12,"labs":36,"learners":120,"quizReferencePresent":true}
```

**Any gate failed:**
```
event: pipeline.done
id: 3
data: {"status":"FAILED"}
```

The summary fields (`specs`, `modules`, `labs`, `learners`, `quizReferencePresent`) are only present when `status` is `"COMPLETED"`. The server closes the stream after this event.

---

## 4. Example handler

```js
const token = getJwt();
const url = `/api/v1/admin/cohorts/${cohortId}/standup/stream?token=${token}`;
const es = new EventSource(url);

es.addEventListener("gate.passed", (e) => {
  const data = JSON.parse(e.data);
  markGatePassed(data.gate, data);       // show green checkmark for gate N
});

es.addEventListener("gate.failed", (e) => {
  const data = JSON.parse(e.data);
  markGateFailed(data.gate, data.errors); // show errors under gate N
});

es.addEventListener("pipeline.done", (e) => {
  const data = JSON.parse(e.data);
  es.close();                             // server already closed its side
  showFinalResult(data.status);           // "COMPLETED" or "FAILED"
});

es.onerror = () => {
  // Browser auto-reconnects with Last-Event-ID.
  // Only call es.close() here if you've already received pipeline.done.
};
```

---

## 5. Payload field types

| Field | Type | Notes |
|---|---|---|
| `gate` | `number` | Gate number (1, 2, or 3) |
| `errors` | `string[]` | Present on `gate.failed` only |
| `driveId` | `string` | Gate 1 pass only |
| `itemId` | `string` | Gate 1 pass only |
| `referenceFolderItemId` | `string` | Gate 2 pass only |
| `specs` | `number` | Gate 3 pass only |
| `modules` | `number` | Gate 3 pass only |
| `labs` | `number` | Gate 3 pass only |
| `learners` | `number` | Gate 3 pass only |
| `quizReferencePresent` | `boolean` | Gate 3 pass only |
| `status` | `string` | `pipeline.done` only — `"COMPLETED"` or `"FAILED"` |
| `specs` | `number` | `pipeline.done` only — present when `status = "COMPLETED"` |
| `modules` | `number` | `pipeline.done` only — present when `status = "COMPLETED"` |
| `labs` | `number` | `pipeline.done` only — present when `status = "COMPLETED"` |
| `learners` | `number` | `pipeline.done` only — present when `status = "COMPLETED"` |
| `quizReferencePresent` | `boolean` | `pipeline.done` only — present when `status = "COMPLETED"` |

---

## 6. Key behaviours

| Concern | Behaviour |
|---|---|
| Auth | JWT goes in `?token=` query param — `EventSource` cannot send `Authorization` headers |
| Reconnect | Browser sends `Last-Event-ID` automatically; server replays missed events from that index |
| Late connect | If you open the stream after the pipeline has finished, all events are replayed and the stream closes immediately |
| Gate failure | Only the failing gate emits `gate.failed`; subsequent gates emit no events — `pipeline.done` follows immediately |
| Post-pipeline state | After `pipeline.done`, poll `GET /api/v1/admin/cohorts/{cohortId}` if you need the full cohort state |
