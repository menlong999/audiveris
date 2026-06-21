package org.audiveris.omr.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.audiveris.omr.util.BaseTestCase;

import org.junit.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

/**
 * Tests for geometry sidecar playback bindings.
 */
public class GeometrySidecarExporterTest
        extends BaseTestCase
{
    @Test
    public void testPlaybackBindingsUsePlayablePartOrdinalsAndPlaybackOrder ()
        throws Exception
    {
        final NoteMapping mapping = new NoteMapping();
        mapping.addSheet(new NoteMapping.SheetInfo(1, 1000, 1000));
        mapping.addSystem(new NoteMapping.SystemInfo(0, 1, new Rectangle(0, 0, 1000, 400)));
        mapping.addMeasure(
                new NoteMapping.MeasureInfo(
                        "P1",
                        "1",
                        1,
                        0,
                        0,
                        0.0,
                        4,
                        1.0,
                        new Rectangle(0, 0, 1000, 200),
                        List.of(new NoteMapping.StaffInfo(0, 0, 100))));
        mapping.addMeasure(
                new NoteMapping.MeasureInfo(
                        "P2",
                        "1",
                        1,
                        0,
                        0,
                        0.0,
                        4,
                        1.0,
                        new Rectangle(0, 200, 1000, 200),
                        List.of(new NoteMapping.StaffInfo(0, 200, 300))));

        mapping.addNote(note(0, 0, "P1", "1", "1", true, null, 0.0));
        mapping.addNote(note(1, 1, "P1", "1", "2", false, "C", 0.0));
        mapping.addNote(note(2, 2, "P1", "1", "1", false, "E", 0.0));
        mapping.addNote(note(0, 3, "P2", "1", "1", false, "G", 0.0));

        final String json = GeometrySidecarExporter.buildJson(mapping, null, null);
        final JsonNode refs = new ObjectMapper().readTree(json).path("playback").path("noteRefs");

        assertEquals(3, refs.size());

        assertEquals("note-2", refs.get(0).path("noteId").asText());
        assertEquals(0, refs.get(0).path("playbackIndex").asInt());
        assertEquals(1, refs.get(0).path("musicXmlNoteOrdinal").asInt());

        assertEquals("note-1", refs.get(1).path("noteId").asText());
        assertEquals(1, refs.get(1).path("playbackIndex").asInt());
        assertEquals(0, refs.get(1).path("musicXmlNoteOrdinal").asInt());

        assertEquals("note-3", refs.get(2).path("noteId").asText());
        assertEquals(2, refs.get(2).path("playbackIndex").asInt());
        assertEquals(0, refs.get(2).path("musicXmlNoteOrdinal").asInt());
    }

    @Test
    public void testSidecarExportsStaffAndOmrProvenanceBindings ()
        throws Exception
    {
        final NoteMapping mapping = new NoteMapping();
        mapping.addSheet(new NoteMapping.SheetInfo(1, 1000, 1000));
        mapping.addSystem(new NoteMapping.SystemInfo(0, 1, new Rectangle(0, 0, 1000, 400)));
        mapping.addMeasure(
                new NoteMapping.MeasureInfo(
                        "P1",
                        "1",
                        1,
                        0,
                        0,
                        0.0,
                        4,
                        1.0,
                        new Rectangle(0, 0, 1000, 200),
                        List.of(new NoteMapping.StaffInfo(1, 0, 100))));
        mapping.addNote(noteWithProvenance());

        final JsonNode root = new ObjectMapper().readTree(GeometrySidecarExporter.buildJson(mapping, null, null));
        final JsonNode sidecarNote = root.path("pages").get(0).path("notes").get(0);
        final JsonNode playbackRef = root.path("playback").path("noteRefs").get(0);

        assertEquals(2, sidecarNote.path("semantic").path("staff").asInt());
        assertEquals(2, playbackRef.path("semantic").path("staff").asInt());

        assertEquals(101, sidecarNote.path("provenance").path("noteInterId").asInt());
        assertEquals(202, sidecarNote.path("provenance").path("chordInterId").asInt());
        assertEquals(303, sidecarNote.path("provenance").path("staffId").asInt());
        assertEquals(404, sidecarNote.path("provenance").path("systemId").asInt());
        assertEquals(505, sidecarNote.path("provenance").path("glyphId").asInt());

        assertEquals(101, playbackRef.path("provenance").path("noteInterId").asInt());
        assertEquals(202, playbackRef.path("provenance").path("chordInterId").asInt());
        assertEquals(303, playbackRef.path("provenance").path("staffId").asInt());
        assertEquals(404, playbackRef.path("provenance").path("systemId").asInt());
        assertEquals(505, playbackRef.path("provenance").path("glyphId").asInt());
    }

    @Test
    public void testSidecarExportsOrdinalChordPhysicalAndRejectEvidence ()
        throws Exception
    {
        final NoteMapping mapping = new NoteMapping();
        mapping.addSheet(new NoteMapping.SheetInfo(1, 1000, 1000));
        mapping.addSystem(new NoteMapping.SystemInfo(0, 1, new Rectangle(0, 0, 1000, 400)));
        mapping.addMeasure(
                new NoteMapping.MeasureInfo(
                        "P1",
                        "1",
                        1,
                        0,
                        0,
                        0.0,
                        4,
                        1.0,
                        new Rectangle(0, 0, 500, 200),
                        List.of(new NoteMapping.StaffInfo(1, 0, 100))));
        mapping.addMeasure(
                new NoteMapping.MeasureInfo(
                        "P1",
                        "2",
                        1,
                        0,
                        4,
                        1.0,
                        4,
                        1.0,
                        new Rectangle(500, 0, 500, 200),
                        List.of(new NoteMapping.StaffInfo(1, 0, 100))));

        mapping.addNote(evidenceNote(0, 0, "1", 0, 0, true, null, 100, 200));
        mapping.addNote(evidenceNote(1, 1, "1", 0, 0, false, "C", 101, 202));
        mapping.addNote(evidenceNote(2, 2, "1", 0, 1, false, "E", 102, 202));
        mapping.addNote(evidenceNote(3, 3, "2", 4, 0, false, "G", 103, 203));
        mapping.addNote(evidenceNote(4, 4, "99", 8, 0, false, "B", 104, 204));

        final JsonNode root = new ObjectMapper().readTree(GeometrySidecarExporter.buildJson(mapping, null, null));
        final JsonNode notes = root.path("pages").get(0).path("notes");

        assertEquals(4, notes.size());

        final JsonNode rest = notes.get(0);
        assertTrue(rest.has("musicXml"));
        assertEquals("P1", rest.path("musicXml").path("xmlPartId").asText());
        assertEquals(0, rest.path("musicXml").path("xmlMeasureIndex").asInt());
        assertEquals("1", rest.path("musicXml").path("xmlMeasureNumber").asText());
        assertEquals(0, rest.path("musicXml").path("xmlNoteOrdinal").asInt());
        assertEquals(0, rest.path("musicXml").path("xmlNoteOrdinalInMeasure").asInt());
        assertTrue(rest.path("musicXml").path("playableOrdinalInPart").isNull());
        assertEquals(0, rest.path("physical").path("physicalMeasureIndex").asInt());
        assertEquals("1", rest.path("physical").path("measureStackScoreId").asText());

        final JsonNode chordRoot = notes.get(1);
        assertEquals(1, chordRoot.path("musicXml").path("xmlNoteOrdinal").asInt());
        assertEquals(1, chordRoot.path("musicXml").path("xmlNoteOrdinalInMeasure").asInt());
        assertEquals(0, chordRoot.path("musicXml").path("playableOrdinalInPart").asInt());

        final JsonNode chordMember = notes.get(2);
        assertEquals(2, chordMember.path("musicXml").path("xmlNoteOrdinal").asInt());
        assertEquals(2, chordMember.path("musicXml").path("xmlNoteOrdinalInMeasure").asInt());
        assertEquals(1, chordMember.path("musicXml").path("playableOrdinalInPart").asInt());

        final JsonNode secondMeasureNote = notes.get(3);
        assertEquals(1, secondMeasureNote.path("musicXml").path("xmlMeasureIndex").asInt());
        assertEquals("2", secondMeasureNote.path("musicXml").path("xmlMeasureNumber").asText());
        assertEquals(0, secondMeasureNote.path("musicXml").path("xmlNoteOrdinalInMeasure").asInt());
        assertEquals(2, secondMeasureNote.path("musicXml").path("playableOrdinalInPart").asInt());

        final JsonNode playbackRef = root.path("playback").path("noteRefs").get(0);
        assertEquals("note-1", playbackRef.path("noteId").asText());
        assertEquals(1, playbackRef.path("musicXml").path("xmlNoteOrdinal").asInt());
        assertEquals(0, playbackRef.path("musicXml").path("playableOrdinalInPart").asInt());
        assertEquals(0, playbackRef.path("physical").path("physicalMeasureIndex").asInt());

        final JsonNode measures = root.path("pages").get(0).path("measures");
        assertEquals("bound", measures.get(0).path("bindingStatus").asText());
        assertEquals("1", measures.get(0).path("physical").path("measureStackId").asText());
        assertEquals("P1", measures.get(0).path("musicXml").path("xmlPartId").asText());

        final JsonNode chord = findChord(root.path("chords"), 202);
        assertEquals(202, chord.path("chordInterId").asInt());
        assertEquals(101, chord.path("rootNoteInterId").asInt());
        assertEquals(2, chord.path("memberNoteInterIds").size());
        assertEquals(101, chord.path("memberNoteInterIds").get(0).asInt());
        assertEquals(102, chord.path("memberNoteInterIds").get(1).asInt());
        assertEquals("root", chord.path("exportedXmlOrdinals").get(0).path("chordRole").asText());
        assertEquals("member", chord.path("exportedXmlOrdinals").get(1).path("chordRole").asText());
        assertEquals("same-chord-inter", chord.path("sameOnset").path("evidence").asText());

        final JsonNode reject = root.path("bindingDiagnostics").path("rejects").get(0);
        assertEquals("measure-unresolved", reject.path("reason").asText());
        assertEquals("note", reject.path("objectType").asText());
        assertEquals(104, reject.path("noteInterId").asInt());
        assertEquals(204, reject.path("chordInterId").asInt());
        assertEquals("P1", reject.path("partId").asText());
        assertEquals("99", reject.path("xmlMeasureNumber").asText());
    }

    private static JsonNode findChord (JsonNode chords,
                                       int chordInterId)
    {
        for (JsonNode chord : chords) {
            if (chord.path("chordInterId").asInt() == chordInterId) {
                return chord;
            }
        }

        throw new AssertionError("Missing chordInterId " + chordInterId);
    }

    private static NoteMapping.NoteEntry note (int noteIndex,
                                               int globalNoteIndex,
                                               String partId,
                                               String measureNumber,
                                               String voice,
                                               boolean isRest,
                                               String step,
                                               double startSeconds)
    {
        final int x = 20 + (globalNoteIndex * 20);
        final int y = "P1".equals(partId) ? 40 : 240;
        final Rectangle noteBounds = new Rectangle(x, y, 10, 10);
        final Rectangle chordBounds = new Rectangle(x - 2, y - 2, 14, 14);
        final Point center = new Point(x + 5, y + 5);

        return new NoteMapping.NoteEntry(
                noteIndex,
                globalNoteIndex,
                partId,
                measureNumber,
                1,
                voice,
                0,
                1,
                0,
                isRest,
                false,
                false,
                false,
                false,
                step,
                isRest ? 0 : 4,
                0,
                isRest ? 0 : midiForStep(step),
                isRest ? 0 : midiForStep(step),
                isRest ? 0.0 : 440.0,
                "quarter",
                0,
                0,
                null,
                0,
                4,
                0,
                startSeconds,
                0.5,
                4,
                0.5,
                noteBounds,
                center,
                chordBounds,
                y - 20,
                y + 20);
    }

    private static NoteMapping.NoteEntry noteWithProvenance ()
    {
        final Rectangle noteBounds = new Rectangle(20, 40, 10, 10);
        final Rectangle chordBounds = new Rectangle(18, 38, 14, 14);
        final Point center = new Point(25, 45);

        return new NoteMapping.NoteEntry(
                0,
                0,
                "P1",
                "1",
                2,
                "1",
                0,
                1,
                0,
                false,
                false,
                false,
                false,
                false,
                "C",
                4,
                0,
                60,
                60,
                440.0,
                "quarter",
                0,
                0,
                null,
                0,
                4,
                0,
                0.0,
                0.5,
                4,
                0.5,
                noteBounds,
                center,
                chordBounds,
                20,
                60,
                101,
                202,
                303,
                404,
                505);
    }

    private static NoteMapping.NoteEntry evidenceNote (int noteIndex,
                                                       int globalNoteIndex,
                                                       String measureNumber,
                                                       int measureCumulativeTimeOffset,
                                                       int noteIndexInChord,
                                                       boolean isRest,
                                                       String step,
                                                       int noteInterId,
                                                       int chordInterId)
    {
        final int x = 20 + (globalNoteIndex * 20);
        final int y = 40;
        final Rectangle noteBounds = new Rectangle(x, y, 10, 10);
        final Rectangle chordBounds = new Rectangle(x - 2, y - 2, 14, 14);
        final Point center = new Point(x + 5, y + 5);

        return new NoteMapping.NoteEntry(
                noteIndex,
                globalNoteIndex,
                "P1",
                measureNumber,
                1,
                "1",
                noteIndexInChord,
                1,
                0,
                isRest,
                false,
                false,
                false,
                false,
                step,
                isRest ? 0 : 4,
                0,
                isRest ? 0 : midiForStep(step),
                isRest ? 0 : midiForStep(step),
                isRest ? 0.0 : 440.0,
                "quarter",
                0,
                0,
                null,
                0,
                4,
                measureCumulativeTimeOffset,
                0.0,
                0.5,
                4,
                0.5,
                noteBounds,
                center,
                chordBounds,
                y - 20,
                y + 20,
                noteInterId,
                chordInterId,
                303,
                404,
                505 + globalNoteIndex);
    }

    private static int midiForStep (String step)
    {
        return switch (step) {
            case "C" -> 60;
            case "E" -> 64;
            case "G" -> 67;
            default -> 60;
        };
    }
}
