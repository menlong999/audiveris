# Audiveris Evidence Export Audit

Generated: 2026-06-21

Repository: `/Users/lvyuanfang/FlutterCode/audiveris`

## Scope

This audit re-evaluates the `menlong999/audiveris` fork evidence export for downstream
`choral_backend` OMR postprocess use. It is intentionally limited to the fork workspace.
No official Audiveris pull request, push, commit, or deployment was performed.

Primary backend evidence sources:

- `/Users/lvyuanfang/FlutterCode/choral_backend/docs/omr-postprocess-11-notehead-bbox-gate-2026-06-21.md`
- `/Users/lvyuanfang/FlutterCode/choral_backend/docs/omr-postprocess-11-notehead-bbox-gate-2026-06-21.summary.json`
- `/Users/lvyuanfang/FlutterCode/choral_backend/docs/omr-postprocess-11-all-bucket-mining-2026-06-21.md`
- `/Users/lvyuanfang/FlutterCode/choral_backend/docs/omr-postprocess-11-all-bucket-mining-2026-06-21.evidence.json`

The backend docs are treated as the archived source of truth. `/tmp` paths mentioned inside those
docs were not used as conclusions.

## Git/Fork State

Current fork state was checked before writing this report:

- `origin` is `git@github.com:menlong999/audiveris.git`.
- Only configured git remote is `origin`.
- Current branch is `master`.
- Current HEAD is `8684a98a11a80b17179906c6f977758c3ebe4a48`
  (`Revert scale peak merge regression`).
- Remote read-only check confirmed `origin/HEAD`, `origin/master`, and
  `origin/codex/sync-upstream-sidecar-provenance` all point to the same `8684a98a11...` commit.
- Local `codex/sync-upstream-sidecar-provenance` is merged into `master`.
- `origin/codex/fix-sidecar-midi-pitch` exists at `da3dfae3...` and is not the current merged
  evidence-export head.

Conclusion: this workspace is on the latest `menlong999/audiveris` `master` observed from `origin`,
and the recent sidecar/provenance branch has been merged into `master`.

Implementation boundary for this fork pass:

- Changes are export-only and additive.
- They serialize Audiveris data that already exists in the export path: MusicXML note order,
  `NoteInter`/`ChordInter` ids, staff/system ids, `MeasureStack` ids, and existing timing fields.
- They do not alter recognition, note/chord grouping, voice assignment, measure construction,
  playback timing calculation, or MusicXML document semantics.
- No upstream PR, push, commit, or deployment was performed.

## Backend Evidence Summary

The latest archived backend gate passed:

- Latest batch: 170/170 terminal.
- Status counts: `SUCCEEDED=157`, `FAILED=13`.
- Successful artifact coverage: `source.xml=157`, `meta.json=157`,
  `performance.json=157`, `source.geometry.sidecar.json=157`.
- Complete success-dirty artifact sets: 157/157.

The all-bucket mining report covered 58 buckets:

- Tier A: 1 bucket.
- Tier B: 12 buckets.
- Tier C: 42 buckets.
- Tier D: 3 buckets.

Aggregate sampled binding result from
`omr-postprocess-11-all-bucket-mining-2026-06-21.evidence.json`:

- Sampled candidates: 2010.
- Uniquely bindable candidates: 121.
- Still ambiguous candidates: 1788.
- Unsafe/conflicting candidates: 149.

Important bucket facts:

- Tier A contains only
  `rule:geometry-backed-lane-timing:geometry-lane-timing-ambiguous-sidecar`.
- That Tier A bucket has `sampledCandidateCount=913`, `uniquelyBindableCount=2`,
  `stillAmbiguousCount=911`.
- `rule:geometry-backed-lane-timing:geometry-lane-timing-missing-measure-ref` is Tier D with
  `sampledCandidateCount=382`, `uniquelyBindableCount=0`, `stillAmbiguousCount=382`.
- Backend change `repair-audiveris-postprocess-12-ordinal-chord-onset` already covers the narrow
  Tier A repair lane. Further backend bbox/geometry expansion is not the right next move.

The mining method explicitly says raw `sourceBBox` or `sourceNoteHeadBBox` pixel coordinates are not
used as normalized geometry tolerance. This audit preserves that boundary.

## Current Export Code Paths

### `source.geometry.sidecar.json` generation

The file export path is:

1. `app/src/main/java/org/audiveris/omr/score/ScoreExporter.java:161` exports MusicXML to a file.
2. `ScoreExporter.export(... Path ...)` calls `exportNoteMapping(path)` and
   `exportGeometrySidecar(path)` after MusicXML writing.
3. `exportNoteMapping` writes sibling `.mapping.json` for `.xml` or `.mxl`.
4. `exportGeometrySidecar` writes sibling `.geometry.sidecar.json`.
5. `exportGeometrySidecar` passes `lastNoteMapping`, `score.getBook().getInputPath()`, and the
   MusicXML path into `GeometrySidecarExporter.buildJson(...)`.

Core references:

- `ScoreExporter.java:124-127`: `PartwiseBuilder.buildWithMapping(score)` returns
  `ScorePartwise` plus `NoteMapping`.
- `ScoreExporter.java:167-176`: file export writes MusicXML, then mapping, then sidecar.
- `ScoreExporter.java:187-208`: raw `.mapping.json` path and write.
- `ScoreExporter.java:223-243`: normalized `.geometry.sidecar.json` path and write.
- `GeometrySidecarExporter.java:77-100`: top-level build from `NoteMapping` sheets/systems/measures/notes.

### MusicXML note export path

MusicXML note export is in `PartwiseBuilder`:

1. `ScoreExporter` calls `PartwiseBuilder.buildWithMapping(score)`.
2. `PartwiseBuilder.processScore()` traverses pages, logical parts, systems, measures, chords, notes.
3. `processChord(AbstractChordInter)` iterates `chord.getNotes()` and calls `processNote(...)`.
4. `processNote(AbstractNoteInter)` creates a ProxyMusic `Note`, fills MusicXML fields, adds
   `<chord/>` for non-root chord members, then appends the note to
   `current.pmMeasure.getNoteOrBackupOrForward()`.
5. Only after the ProxyMusic note is appended, `collectNoteMapping(...)` records the exported note.

Core references:

- `PartwiseBuilder.java:1416-1422`: chord traversal.
- `PartwiseBuilder.java:2689-2694`: current note/chord and ProxyMusic note creation.
- `PartwiseBuilder.java:2725-2728`: non-first chord member gets MusicXML `<chord/>`.
- `PartwiseBuilder.java:3058-3063`: exported ProxyMusic note is appended, then mapping is collected.
- `PartwiseBuilder.java:4035-4045`: `buildWithMapping(...)`.

This ordering is good: collected `NoteMapping.NoteEntry` entries correspond to notes that reached
the MusicXML output path, excluding dummy measures and repeat-copying.

### Playback / `noteRefs` generation

`GeometrySidecarExporter.appendPlayback(...)` builds `playback.noteRefs`:

- It filters to playable notes with `!isRest && midiPitch != null`.
- It sorts playable notes by `globalNoteIndex` to define per-part `musicXmlNoteOrdinal`.
- It sorts playback refs by `timingMs.start -> partId -> voice -> musicXmlNoteOrdinal -> globalNoteIndex`.
- It writes `noteId`, `playbackIndex`, `musicXmlNoteOrdinal`, semantic fields, timing, and provenance.

Core references:

- `GeometrySidecarExporter.java:594-663`: playback note refs.
- `GeometrySidecarExporter.java:665-668`: playable filter.
- `docs/NoteMapping-Specification.md:61-72`: documented meaning of `musicXmlNoteOrdinal` and
  `playbackIndex`.

Audit-start limitation: `musicXmlNoteOrdinal` was only present on playable `playback.noteRefs`, not
on every exported MusicXML note, and not scoped by measure.

### Provenance/raw ID sources

The raw ID fields come from Audiveris graph objects during `collectNoteMapping(...)`:

- `noteInterId`: `note.getId()`.
- `chordInterId`: `chord.getId()`.
- `staffId`: `staff.getId()`.
- `systemId`: `current.system.getId()`.
- `glyphId`: `note.getGlyph().getId()`.

Core references:

- `PartwiseBuilder.java:2480-2670`: `collectNoteMapping(...)`.
- `PartwiseBuilder.java:2654-2658`: positive ID extraction.
- `NoteMapping.java:598-767`: `NoteEntry` field contract.
- `NoteMapping.java:366-372`: raw mapping provenance JSON.
- `GeometrySidecarExporter.java:670-680`: sidecar/playback provenance JSON.

Physical measure ID source:

- `MeasureStack` documents page-based measure IDs and score-based exported IDs.
- `PartwiseBuilder` sets MusicXML measure number from `stack.getScoreId(current.pageMeasureIdOffset)`.
- At audit start, the sidecar did not export `MeasureStack.getPageId()`, raw stack id, score id,
  or stack index.

Core references:

- `MeasureStack.java:87-100`: page-based and score-based measure ID model.
- `MeasureStack.java:1122-1130`: `getScoreId(pageMeasureIdOffset)`.
- `PartwiseBuilder.java:2098-2100`: MusicXML measure number assignment.

## Current Exported Fields

At audit start, raw `.mapping.json` included:

- `divisions`, `tempos`, `timeSignatures`, `keySignatures`.
- `sheets`: `sheetNumber`, `imageWidth`, `imageHeight`.
- `systems`: `systemIndex`, `sheetNumber`, raw bounds.
- `measures`: `partId`, `measureNumber`, `sheetNumber`, `systemIndex`, cumulative time,
  measure duration, raw bounds, staff vertical ranges.
- `notes`: `noteIndex`, `globalNoteIndex`, `partId`, `measureNumber`, `staff`, `voice`,
  `noteIndexInChord`, `sheetNumber`, `systemIndex`, rest/grace/tie flags, pitch, type, dots,
  stem direction, beam group, time/duration, tied duration, raw note/chord bounds, staff vertical
  range, provenance IDs.

At audit start, normalized `.geometry.sidecar.json` included:

- `schemaVersion`, `generatedAt`, `engine`, `source`, `coordinateSpace`.
- Per page: page indices, source/raster metadata, transform.
- Per page systems: `id`, normalized `bbox`, `orderInPage`, `measureIds`, `noteIds`.
- Per page measures: `id`, normalized `bbox`, `pageIndex`, `systemId`, `partId`, `measureIndex`,
  `measureNumber`, `noteIds`, raw `sourceBBox`.
- Per page notes: `id`, normalized `bbox`, normalized `noteHeadBBox`, `pageIndex`, `systemId`,
  `measureId`, `semantic`, `flags`, `timingMs`, `raw`, `provenance`, raw `sourceBBox`,
  raw `sourceNoteHeadBBox`.
- Top-level `playback.noteRefs`: playable notes only, with `playbackIndex`,
  `musicXmlNoteOrdinal`, semantic fields, timing, and provenance.

Existing tests:

- `GeometrySidecarExporterTest.java:20-73` verifies playable per-part ordinals and playback order.
- `GeometrySidecarExporterTest.java:75-113` verifies staff and OMR provenance appear on notes and
  playback refs.

Verification update:

- Homebrew OpenJDK was found at `/opt/homebrew/opt/openjdk`.
- Baseline focused test passed with:
  `JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk/bin:$PATH ./gradlew :app:test --tests org.audiveris.omr.score.GeometrySidecarExporterTest`
- A RED fixture was added for A-D additive evidence. It failed as expected while `musicXml` was
  absent from sidecar notes.
- After the minimal export implementation, the focused sidecar test passed.

## Implemented Minimal A-D Slice

This fork pass implements the smallest useful A-D sidecar slice under focused TDD. It is not a
full provenance system yet; it establishes stable, backend-consumable identities without changing
Audiveris behavior.

Changed code:

- `app/src/main/java/org/audiveris/omr/score/PartwiseBuilder.java`
  - Captures physical measure context while the existing note mapping is collected.
  - Adds `MeasureStack` page id, score id, stack index, system id, system order, and staff ids.
- `app/src/main/java/org/audiveris/omr/score/NoteMapping.java`
  - Adds optional measure fields to raw `.mapping.json`.
  - Keeps the old `MeasureInfo` constructor, so existing call sites remain compatible.
- `app/src/main/java/org/audiveris/omr/score/GeometrySidecarExporter.java`
  - Adds `musicXml`, `physical`, top-level `chords`, and top-level `bindingDiagnostics`.
- `app/src/test/java/org/audiveris/omr/score/GeometrySidecarExporterTest.java`
  - Adds a fixture with a rest, a two-note chord, a second physical measure, and a deliberately
    unresolved measure reference.

Implemented A fields:

- `pages[].notes[].musicXml.xmlPartId`
- `pages[].notes[].musicXml.xmlMeasureIndex`
- `pages[].notes[].musicXml.xmlMeasureNumber`
- `pages[].notes[].musicXml.xmlNoteOrdinal`
- `pages[].notes[].musicXml.xmlNoteOrdinalInMeasure`
- `pages[].notes[].musicXml.playableOrdinalInPart`
- `pages[].notes[].musicXml.exported`
- equivalent `musicXml` on `playback.noteRefs[]`

Implemented B fields:

- top-level `chords[]`
- exported non-rest note membership grouped by scoped `(chordInterId, measureId)`
- `chordInterId`
- `rootNoteInterId`
- `memberNoteInterIds`
- `exportedNoteIds`
- `exportedXmlOrdinals[]`
- `chordRole`
- `sameOnset.evidence = "same-chord-inter"`
- source part/staff/system/measure identity

Implemented C fields:

- `pages[].measures[].musicXml`
- `pages[].measures[].physical`
- `pages[].measures[].bindingStatus = "bound"`
- `pages[].notes[].physical`
- `playback.noteRefs[].physical`
- raw `.mapping.json` measure physical fields

Implemented D fields:

- top-level `bindingDiagnostics.rejects[]`
- deterministic note reject reason `measure-unresolved`
- top-level `bindingDiagnostics.conflicts: []` placeholder for future bounded conflict evidence

## Why Backend Finds Few Uniquely Bindable Candidates

The audit-start sidecar was useful for viewer overlays and basic playback highlighting, but it was
not yet a direct MusicXML timeline to OMR graph binding contract.

Main blockers:

1. MusicXML ordinal provenance is partial.
   `playback.noteRefs.musicXmlNoteOrdinal` covers only playable notes and is scoped to `partId`.
   It does not cover rests, grace notes without MIDI pitch, measure rests, chord members that are
   needed for structural diagnostics, or every exported `<note>` in document order.

2. The pre-slice sidecar note object did not expose full MusicXML ordinal identity.
   It has `raw.globalNoteIndex` and `raw.noteIndex`, but no explicit `xmlNoteOrdinal`,
   `xmlNoteOrdinalInMeasure`, or `playableOrdinalInPart` on every note. Backend therefore has to
   infer whether a sidecar note corresponds to a specific MusicXML `<note>` node.

3. Chord graph membership is not exported as a first-class relation.
   Raw mapping includes `chordInterId` and `noteIndexInChord`; sidecar includes `chordInterId` but
   dropped `noteIndexInChord` and had no `chords[]` membership table. Backend therefore had to
   infer same-onset groups and `<chord/>` root/member decisions from geometry/timing.

4. Physical measure binding is incomplete.
   Sidecar measures contain `measureIndex` and `measureNumber`, but not the physical
   `MeasureStack` page id, score id, stack index in system, or a bounded duplicate/part/staff
   mismatch status. This was the likely upstream cause for the Tier D
   `geometry-lane-timing-missing-measure-ref` bucket.

5. Negative evidence is absent.
   At audit start, the exporter logged exceptions but did not serialize bounded reject reasons for
   graph objects that were not exported or could not be bound. Backend therefore collapsed many
   cases into "no unique latest sidecar/provenance/playback ordinal/MusicXML timeline binding
   found".

6. Timing is advisory rather than authoritative.
   Playback ordering is derived from `timingMs.start`, part, voice, and ordinal. That is fine for a
   player but insufficient as a mutation proof for underfill/overfill, backup/forward, ties, tuplets,
   and cross-system marker scope.

This explains the mining result: even though 157/157 successful artifacts have sidecars, only a
small minority of sampled residuals have unique binding proof. The missing evidence is relational
and ordinal, not another bbox tolerance problem.

## Pre-Slice Code Review Findings

### P1: No full exported-note ordinal map

`globalNoteIndex` and per-part `noteIndex` are present in raw mapping, and playable per-part
`musicXmlNoteOrdinal` is present in `playback.noteRefs`. The backend needs an explicit ordinal map
for every exported MusicXML `<note>`:

- all notes, rests, grace notes, and measure rests that were exported;
- part-scoped and measure-scoped ordinals;
- playable ordinal only when applicable;
- direct links to `noteInterId`, `chordInterId`, staff/system/measure provenance.

Without this, backend document-order validators still need to guess from XML traversal plus
sidecar order.

### P1: Chord/root membership is flattened away

MusicXML `<chord/>` semantics are available in `processNote(...)`: the first note in
`chord.getNotes()` is the root, non-first notes receive `current.pmNote.setChord(new Empty())`.
At audit start, the sidecar exported only `chordInterId`. Raw mapping had `noteIndexInChord`, but
normalized sidecar dropped it and did not provide a
`chordInterId -> member notes -> exported ordinals` table.

This is why same-onset / chord-onset residuals remain expensive for backend to prove safely.

### P1: Physical measure provenance stops at synthetic sidecar IDs

The existing sidecar `measureId` is synthesized as
`page-{sheetNumber}-system-{systemIndex+1}-part-{partId}-measure-{measureIndex+1}`. It is stable
inside the sidecar but is not the Audiveris physical measure stack id and is not the MusicXML
measure score id.

Backend needs both:

- physical identity: page/system/stack/part/staff source;
- exported XML identity: part/measure index/measure number.

### P2: No bounded reject/ambiguity export

The exporter skips dummy measures and repeat-copying by design. It also catches mapping exceptions
and logs warnings. At audit start, these skipped or failed states were not serialized. For backend
triage, a bounded reject reason is more valuable than silence.

### P2: Manual JSON building increases additive-field risk

`NoteMapping` and `GeometrySidecarExporter` manually concatenate JSON strings. This is workable for
small additive changes but raises comma/escaping regression risk as evidence export grows beyond
this minimal slice. If implementation continues into richer D/E/F/G payloads, switch the sidecar
builder to a structured JSON writer or introduce a small tested append helper for nested objects.

## Recommended Evidence Export Design

All fields below are additive and optional. Existing fields should remain unchanged so older
backend/Flutter consumers keep working.

Recommended schema version: keep `schemaVersion: "1.0"` if consumers treat it as informational, or
move to `"1.1"` only if consumers explicitly support compatible minor versions. Do not remove or
rename current `pages[].notes[]`, `playback.noteRefs[]`, `semantic`, `raw`, or `provenance`.

### A. MusicXML ordinal provenance map

Add to every `pages[].notes[]` entry:

```json
{
  "musicXml": {
    "xmlPartId": "P1",
    "xmlMeasureIndex": 7,
    "xmlMeasureNumber": "7",
    "xmlNoteOrdinal": 143,
    "xmlNoteOrdinalInMeasure": 5,
    "playableOrdinalInPart": 97,
    "exported": true
  }
}
```

Add equivalent compact fields to `playback.noteRefs[]`:

```json
{
  "noteId": "note-143",
  "playbackIndex": 91,
  "musicXmlNoteOrdinal": 97,
  "musicXml": {
    "xmlPartId": "P1",
    "xmlMeasureIndex": 7,
    "xmlMeasureNumber": "7",
    "xmlNoteOrdinal": 143,
    "xmlNoteOrdinalInMeasure": 5,
    "playableOrdinalInPart": 97
  }
}
```

Backend consumption contract:

- `xmlPartId + xmlNoteOrdinal` identifies every exported note within a part.
- `xmlPartId + xmlMeasureIndex + xmlNoteOrdinalInMeasure` identifies every exported note inside a
  physical/exported measure context.
- `playableOrdinalInPart` is nullable and only applies to notes in playback refs.

Implementation notes:

- Existing `NoteMapping.NoteEntry.noteIndex` is already a per-part exported note counter; expose it
  as `xmlNoteOrdinal`.
- Existing sidecar `measureIndex` is a per-part exported measure index; expose it as
  `xmlMeasureIndex`.
- Add or compute a per-measure counter for `xmlNoteOrdinalInMeasure`.
- Reuse the existing playable ordinal map for `playableOrdinalInPart`.

### B. Chord/group membership export

Add a top-level `chords` array or per-page `chords` array. Top-level is easier for backend lookup by
`chordInterId`.

```json
{
  "chords": [
    {
      "id": "chord-202",
      "chordInterId": 202,
      "rootNoteInterId": 101,
      "memberNoteInterIds": [101, 102, 103],
      "exportedNoteIds": ["note-143", "note-144", "note-145"],
      "exportedXmlOrdinals": [
        {
          "noteId": "note-143",
          "xmlPartId": "P1",
          "xmlMeasureIndex": 7,
          "xmlNoteOrdinal": 143,
          "xmlNoteOrdinalInMeasure": 5,
          "noteIndexInChord": 0,
          "chordRole": "root"
        },
        {
          "noteId": "note-144",
          "xmlPartId": "P1",
          "xmlMeasureIndex": 7,
          "xmlNoteOrdinal": 144,
          "xmlNoteOrdinalInMeasure": 6,
          "noteIndexInChord": 1,
          "chordRole": "member"
        }
      ],
      "sameOnset": {
        "startDivision": 384,
        "durationDivisions": 192,
        "voiceRaw": "1",
        "staff": 1,
        "evidence": "same-chord-inter"
      },
      "source": {
        "partId": "P1",
        "staffId": 303,
        "systemId": 404,
        "measureId": "page-1-system-1-part-P1-measure-8"
      }
    }
  ]
}
```

Backend consumption contract:

- Prefer `same-chord-inter` over bbox/onset clustering for `<chord/>` repair proof.
- Treat `chordRole=root/member` as Audiveris export intent.
- Reject if a MusicXML chord marker conflicts with `chordInterId` membership.
- Do not key top-level chord evidence by bare `chordInterId`. Use `chords[].id` or
  `(source.measureId, chordInterId)`, because Audiveris `Inter` ids can be reused across source
  graph scopes in multi-page exports.

Implementation notes:

- Preserve `noteIndexInChord` in normalized `NoteState`.
- Group `NoteState` by non-null `chordInterId` plus `measureId`.
- Root is `noteIndexInChord == 0`; members are `> 0`.
- `sameOnset` can be exported from current `startDivision`, `durationDivisions`, `voiceRaw`, staff,
  `chordInterId`, and scoped measure identity.

### C. Physical measure binding

Add to every `pages[].measures[]` entry:

```json
{
  "physical": {
    "physicalMeasureIndex": 7,
    "measureStackId": "7",
    "measureStackScoreId": "7",
    "pageIndex": 0,
    "pageNumber": 1,
    "systemId": 404,
    "systemOrderInPage": 0,
    "partId": "P1",
    "staffIds": [303]
  },
  "musicXml": {
    "xmlPartId": "P1",
    "xmlMeasureIndex": 7,
    "xmlMeasureNumber": "7"
  },
  "bindingStatus": "bound"
}
```

Add to every note and playback ref:

```json
{
  "physical": {
    "physicalMeasureIndex": 7,
    "measureStackId": "7",
    "measureStackScoreId": "7",
    "pageIndex": 0,
    "systemId": 404,
    "partId": "P1",
    "staffId": 303
  }
}
```

Add bounded measure rejects:

```json
{
  "measureBindingRejects": [
    {
      "reason": "measure-number-duplicate",
      "partId": "P1",
      "xmlMeasureNumber": "7",
      "candidateMeasureIds": [
        "page-1-system-1-part-P1-measure-8",
        "page-1-system-2-part-P1-measure-9"
      ]
    }
  ]
}
```

Allowed `reason` values:

- `missing-measure-stack`
- `no-exported-xml-measure`
- `measure-number-duplicate`
- `staff-part-mismatch`
- `not-exported-to-musicxml`

Implementation notes:

- Extend `NoteMapping.MeasureInfo` with optional physical fields:
  `measureStackPageId`, `measureStackScoreId`, `physicalMeasureIndex`, `systemId`,
  `systemOrderInPage`, `staffIds`.
- Populate from `MeasureStack.getPageId()`, `MeasureStack.getScoreId(...)`,
  `current.system.getStacks().indexOf(stack)`, `current.system.getId()`, and part staves.
- Do not infer physical binding from bbox.

### D. Ambiguity / negative evidence

Add a top-level `bindingDiagnostics` object:

```json
{
  "bindingDiagnostics": {
    "rejects": [
      {
        "reason": "missing-voice",
        "objectType": "chord",
        "chordInterId": 202,
        "partId": "P1",
        "xmlMeasureIndex": 7,
        "measureStackId": "7"
      },
      {
        "reason": "omr-object-not-exported",
        "objectType": "note",
        "noteInterId": 901,
        "chordInterId": 902,
        "partId": "P1",
        "measureStackId": "7"
      }
    ],
    "conflicts": [
      {
        "reason": "ordinal-conflict",
        "xmlPartId": "P1",
        "xmlMeasureIndex": 7,
        "xmlNoteOrdinalInMeasure": 5,
        "candidateNoteInterIds": [101, 109]
      }
    ]
  }
}
```

Allowed `reason` values:

- `multiple-candidate-notes`
- `multiple-candidate-chords`
- `missing-staff`
- `missing-voice`
- `missing-playback-ordinal`
- `ordinal-conflict`
- `measure-unresolved`
- `omr-object-not-exported`

Implementation notes:

- Start with rejects that are easy and deterministic:
  missing staff, missing voice, skipped repeat-copying, dummy/not-exported measure, missing measure
  stack.
- Keep payload bounded. Do not export image bytes, signed URLs, source paths for OCR blobs, or raw
  large OCR text.

## Optional Later Exports

### E. Tie/tuplet/direction/octave provenance

Export marker relation/group id, start/stop endpoints, linked note ordinals, and staff/system scope:

- tie/slur relation id, start/end `noteInterId`, start/end `xmlNoteOrdinal`;
- tuplet id, member chord ids, member note ordinals, base/actual/normal counts;
- wedge/octave shift ids, start/stop chord ids, staff/system boundary status.

This should wait until A-D exists, because marker provenance is only useful when endpoint notes and
physical measures have stable IDs.

### F. Voice assignment trace

Export normalized `voice`, raw `voiceRaw`, candidate voices, assignment reason, and confidence when
available. Current sidecar already has `voice` and `voiceRaw`; it does not explain assignment.

### G. Lyric OCR lineage

Export bounded OCR candidate/slot/note binding/reject reason only. Do not export image bytes, file
paths to OCR crops, signed URLs, or unbounded raw OCR text.

## Recommended Implementation Order

1. A: MusicXML ordinal provenance map.
   This is the smallest high-ROI slice. It mostly re-labels existing counters and adds a
   per-measure ordinal. It directly helps backend bind MusicXML timeline nodes to OMR graph IDs.

2. B: Chord/group membership export.
   This unlocks same-onset and `<chord/>` proof without bbox/onset guessing. It can be derived from
   current `chordInterId` plus preserved `noteIndexInChord`.

3. C: Physical measure binding.
   This addresses `missing-measure-ref` and duplicate measure-number ambiguity. It requires adding
   fields to `MeasureInfo` collection in `PartwiseBuilder`.

4. D: Ambiguity / negative evidence.
   This should follow A-C so reject reasons can cite stable note/chord/measure identities.

5. E/F/G only after backend consumes A-D and proves new unique binding coverage.

## Minimal TDD Slice Executed

The fixture in `GeometrySidecarExporterTest` covers A-D together because the same exported notes
need all four identities to be useful to the backend.

RED test covered:

- every exported `pages[0].notes[]` entry has `musicXml`;
- rests have `playableOrdinalInPart: null`;
- playable notes expose stable playable ordinals;
- a two-note `chordInterId` exports root/member evidence and same-onset evidence;
- measures, notes, and playback refs expose physical measure binding;
- an intentionally unresolved measure note is emitted as a bounded `measure-unresolved` reject.
- a reused `chordInterId` in a different measure scope is not merged into the same top-level
  `chords[]` evidence object.

Focused verification command:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk/bin:$PATH ./gradlew :app:test --tests org.audiveris.omr.score.GeometrySidecarExporterTest
```

Result: passed.

## 2026-06-22 Corpus Follow-Up

After rebuilding the arm64 base and worker images from fork commit `91022729a5...`, the 170 PDF
category corpus reached terminal state with `SUCCEEDED=159` and `FAILED=11`. Backend read-only gate
passed with `completeSuccessDirtyArtifactSets=157/157`.

Real artifact inspection across the 159 succeeded jobs confirmed:

- `source.geometry.sidecar.json` fetched and parsed for 159/159 succeeded jobs;
- every fetched sidecar had per-note `musicXml` and `physical` evidence;
- every fetched sidecar had per-measure `musicXml` and `physical` evidence;
- every fetched sidecar had top-level `chords[]` and `bindingDiagnostics`;
- playback refs had nested `musicXml`/`physical` in 154/159 succeeded jobs; the remaining jobs need
  separate playback-ref investigation, but per-note ordinal evidence is present.

The same inspection exposed a B-slice bug in the initial implementation: grouping top-level
`chords[]` by bare `chordInterId` merged unrelated notes when an Audiveris inter id was reused in a
different measure/part/system scope. The observed pre-fix export had 34,292 conflicting chord
objects across 103 files. The fork now scopes chord grouping by `(chordInterId, measureId)` and
keeps `chordInterId` as provenance rather than as the globally unique chord key.

## Backend-Consumable Example

Suggested additive note payload:

```json
{
  "id": "note-143",
  "bbox": {"x": 0.3125, "y": 0.4201, "width": 0.018, "height": 0.014},
  "noteHeadBBox": {"x": 0.315, "y": 0.423, "width": 0.012, "height": 0.01},
  "pageIndex": 0,
  "systemId": "page-1-system-1",
  "measureId": "page-1-system-1-part-P1-measure-8",
  "semantic": {
    "partId": "P1",
    "voice": 1,
    "voiceRaw": "1",
    "staff": 1,
    "measureIndex": 7,
    "measureNumber": "7",
    "startDivision": 384,
    "durationDivisions": 192,
    "midiPitch": 64,
    "occurrence": 0
  },
  "musicXml": {
    "xmlPartId": "P1",
    "xmlMeasureIndex": 7,
    "xmlMeasureNumber": "7",
    "xmlNoteOrdinal": 143,
    "xmlNoteOrdinalInMeasure": 5,
    "playableOrdinalInPart": 97,
    "exported": true
  },
  "physical": {
    "physicalMeasureIndex": 7,
    "measureStackId": "7",
    "measureStackScoreId": "7",
    "pageIndex": 0,
    "systemId": 404,
    "partId": "P1",
    "staffId": 303
  },
  "provenance": {
    "noteInterId": 101,
    "chordInterId": 202,
    "staffId": 303,
    "systemId": 404,
    "glyphId": 505
  }
}
```

Suggested additive chord payload:

```json
{
  "id": "chord-202",
  "chordInterId": 202,
  "rootNoteInterId": 101,
  "memberNoteInterIds": [101, 102],
  "exportedNoteIds": ["note-143", "note-144"],
  "exportedXmlOrdinals": [
    {
      "noteId": "note-143",
      "xmlPartId": "P1",
      "xmlMeasureIndex": 7,
      "xmlNoteOrdinal": 143,
      "xmlNoteOrdinalInMeasure": 5,
      "noteIndexInChord": 0,
      "chordRole": "root"
    },
    {
      "noteId": "note-144",
      "xmlPartId": "P1",
      "xmlMeasureIndex": 7,
      "xmlNoteOrdinal": 144,
      "xmlNoteOrdinalInMeasure": 6,
      "noteIndexInChord": 1,
      "chordRole": "member"
    }
  ],
  "sameOnset": {
    "startDivision": 384,
    "durationDivisions": 192,
    "voiceRaw": "1",
    "staff": 1,
    "evidence": "same-chord-inter"
  }
}
```

Suggested additive diagnostics payload:

```json
{
  "bindingDiagnostics": {
    "rejects": [
      {
        "reason": "not-exported-to-musicxml",
        "objectType": "note",
        "noteInterId": 901,
        "chordInterId": 902,
        "partId": "P1",
        "measureStackId": "7"
      },
      {
        "reason": "measure-number-duplicate",
        "objectType": "measure",
        "partId": "P1",
        "xmlMeasureNumber": "7",
        "candidateMeasureIds": [
          "page-1-system-1-part-P1-measure-8",
          "page-1-system-2-part-P1-measure-9"
        ]
      }
    ]
  }
}
```

## Final Recommendation

Do not continue broad backend bbox/geometry repair. The current backend gate already passed and the
remaining high-volume buckets in the archived batch were mostly ambiguous because that export
lacked explicit ordinal, chord, physical-measure, and negative evidence.

The fork now has a minimal additive A-D export slice. Next recommended steps are:

1. Run this fork against a small known corpus sample and diff the generated sidecars for schema
   stability.
2. Teach backend consumers to prefer `musicXml`, `chords`, `physical`, and
   `bindingDiagnostics` before falling back to bbox/geometry heuristics.
3. Expand D from `measure-unresolved` to the full bounded reason set only where Audiveris has
   deterministic source evidence.
4. Defer E/F/G until backend proves A-D increases uniquely bindable candidates.
