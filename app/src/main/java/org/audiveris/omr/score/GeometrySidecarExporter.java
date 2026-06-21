//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                             G e o m e t r y S i d e c a r E x p o r t e r                      //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2025. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.score;

import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a normalized geometry sidecar JSON file from the low-level note mapping export.
 * <p>
 * The legacy {@code .mapping.json} output remains focused on raw OMR pixel coordinates and
 * timing hints, while this sidecar reshapes the same information into a hierarchy that is easier
 * to consume from a score viewer:
 * page -> system -> measure -> note.
 *
 * @author Hervé Bitteur
 */
public final class GeometrySidecarExporter
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(GeometrySidecarExporter.class);

    //~ Constructors -------------------------------------------------------------------------------

    private GeometrySidecarExporter ()
    {
    }

    //~ Methods ------------------------------------------------------------------------------------

    /**
     * Build the geometry sidecar JSON string.
     *
     * @param noteMapping the collected note mapping
     * @param inputPath the source input path, if any
     * @param musicXmlPath the exported MusicXML path
     * @return the JSON payload
     */
    public static String buildJson (NoteMapping noteMapping,
                                    Path inputPath,
                                    Path musicXmlPath)
    {
        Objects.requireNonNull(noteMapping, "noteMapping");

        final List<NoteMapping.SheetInfo> sheets = noteMapping.getSheets();
        final List<NoteMapping.SystemInfo> systems = noteMapping.getSystems();
        final List<NoteMapping.MeasureInfo> measures = noteMapping.getMeasures();
        final List<NoteMapping.NoteEntry> notes = noteMapping.getNotes();
        final Map<Integer, NoteMapping.SheetInfo> sheetByNumber = new LinkedHashMap<>();

        for (NoteMapping.SheetInfo sheet : sheets) {
            sheetByNumber.put(sheet.sheetNumber, sheet);
        }

        final String sourceKind = detectSourceKind(inputPath);
        final Map<Integer, SourcePageInfo> sourcePages = resolveSourcePages(inputPath, sourceKind, sheetByNumber);
        final Map<String, Integer> measureIndexByKey = buildMeasureIndexMap(measures);
        final BindingDiagnostics diagnostics = new BindingDiagnostics();
        final Map<String, MeasureState> measureStateByKey = buildMeasureStates(
                measures,
                measureIndexByKey,
                sheetByNumber);
        final List<NoteState> noteStates = buildNoteStates(
                notes,
                measureStateByKey,
                sheetByNumber,
                diagnostics);
        final Map<String, Integer> playableOrdinalByNoteId = buildPlayableOrdinalByNoteId(noteStates);

        final StringBuilder sb = new StringBuilder(32_768);
        sb.append("{\n");
        sb.append("  \"schemaVersion\": \"1.0\",\n");
        sb.append("  \"generatedAt\": ").append(jsonString(Instant.now().toString())).append(",\n");
        sb.append("  \"engine\": \"audiveris-omr\",\n");
        sb.append("  \"source\": {\n");
        sb.append("    \"kind\": ").append(jsonString(sourceKind)).append(",\n");
        sb.append("    \"inputPath\": ").append(jsonString((inputPath != null) ? inputPath.toString() : null)).append(",\n");
        sb.append("    \"musicXmlPath\": ").append(jsonString((musicXmlPath != null) ? musicXmlPath.toString() : null)).append(",\n");
        sb.append("    \"pageCount\": ").append(sheetByNumber.size()).append("\n");
        sb.append("  },\n");
        sb.append("  \"coordinateSpace\": {\n");
        sb.append("    \"canonical\": \"page-normalized\",\n");
        sb.append("    \"origin\": \"top-left\",\n");
        sb.append("    \"axes\": \"x-right-y-down\"\n");
        sb.append("  },\n");
        sb.append("  \"pages\": [\n");

        final List<Integer> pageNumbers = new ArrayList<>(sheetByNumber.keySet());
        pageNumbers.sort(Integer::compareTo);

        for (int pageIdx = 0; pageIdx < pageNumbers.size(); pageIdx++) {
            final int pageNumber = pageNumbers.get(pageIdx);
            final NoteMapping.SheetInfo sheet = sheetByNumber.get(pageNumber);
            final SourcePageInfo sourcePage = sourcePages.get(pageNumber);

            final List<SystemState> pageSystems = buildSystemStates(pageNumber, systems, sheet, measureStateByKey, noteStates);
            final List<MeasureState> pageMeasures = buildPageMeasures(pageNumber, measureStateByKey.values());
            final List<NoteState> pageNotes = buildPageNotes(pageNumber, noteStates);

            sb.append("    {\n");
            sb.append("      \"pageIndex\": ").append(pageNumber - 1).append(",\n");
            sb.append("      \"pageNumber\": ").append(pageNumber).append(",\n");
            sb.append("      \"sourcePage\": {\n");
            sb.append("        \"kind\": ").append(jsonString(sourcePage.kind)).append(",\n");
            sb.append("        \"width\": ").append(number(sourcePage.width)).append(",\n");
            sb.append("        \"height\": ").append(number(sourcePage.height)).append(",\n");
            sb.append("        \"unit\": ").append(jsonString(sourcePage.unit)).append(",\n");
            if (sourcePage.rotationDegrees != null) {
                sb.append("        \"rotationDegrees\": ").append(sourcePage.rotationDegrees).append("\n");
            } else {
                sb.append("        \"rotationDegrees\": null\n");
            }
            sb.append("      },\n");
            sb.append("      \"raster\": {\n");
            sb.append("        \"width\": ").append(sheet.imageWidth).append(",\n");
            sb.append("        \"height\": ").append(sheet.imageHeight).append(",\n");
            sb.append("        \"unit\": \"px\"\n");
            sb.append("      },\n");
            sb.append("      \"transform\": {\n");
            sb.append("        \"from\": \"raster-px\",\n");
            sb.append("        \"to\": \"page-normalized\",\n");
            sb.append("        \"matrix\": [")
                    .append(number(1.0 / Math.max(1, sheet.imageWidth))).append(", 0, 0, ")
                    .append(number(1.0 / Math.max(1, sheet.imageHeight))).append(", 0, 0]\n");
            sb.append("      },\n");

            appendSystems(sb, pageSystems, "      ");
            sb.append(",\n");
            appendMeasures(sb, pageMeasures, "      ");
            sb.append(",\n");
            appendNotes(sb, pageNotes, playableOrdinalByNoteId, "      ");
            sb.append("\n");
            sb.append("    }");
            if (pageIdx < pageNumbers.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("  ],\n");
        appendPlayback(sb, noteStates, playableOrdinalByNoteId, "  ");
        sb.append(",\n");
        appendChords(sb, noteStates, "  ");
        sb.append(",\n");
        appendBindingDiagnostics(sb, diagnostics, "  ");
        sb.append("\n");
        sb.append("}\n");

        return sb.toString();
    }

    private static Map<Integer, SourcePageInfo> resolveSourcePages (Path inputPath,
                                                                    String sourceKind,
                                                                    Map<Integer, NoteMapping.SheetInfo> sheetByNumber)
    {
        final Map<Integer, SourcePageInfo> sourcePages = new HashMap<>();

        if ((inputPath != null) && "pdf".equals(sourceKind) && Files.exists(inputPath)) {
            try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(
                    new RandomAccessReadBufferedFile(inputPath.toString()))) {
                final int count = Math.min(document.getNumberOfPages(), sheetByNumber.size());

                for (int index = 0; index < count; index++) {
                    final PDRectangle box = document.getPage(index).getMediaBox();
                    final int rotation = document.getPage(index).getRotation();
                    sourcePages.put(index + 1, new SourcePageInfo(
                            "pdf",
                            box.getWidth(),
                            box.getHeight(),
                            "pt",
                            rotation));
                }
            } catch (IOException ex) {
                logger.warn("Could not resolve PDF page geometry for {}", inputPath, ex);
            }
        }

        for (NoteMapping.SheetInfo sheet : sheetByNumber.values()) {
            sourcePages.computeIfAbsent(
                    sheet.sheetNumber,
                    key -> new SourcePageInfo(
                            sourceKind,
                            sheet.imageWidth,
                            sheet.imageHeight,
                            "px",
                            null));
        }

        return sourcePages;
    }

    private static String detectSourceKind (Path inputPath)
    {
        if (inputPath == null) {
            return "unknown";
        }

        final String lower = inputPath.getFileName().toString().toLowerCase(Locale.ROOT);

        if (lower.endsWith(".pdf")) {
            return "pdf";
        }

        return "image";
    }

    private static Map<String, Integer> buildMeasureIndexMap (List<NoteMapping.MeasureInfo> measures)
    {
        final Map<String, Integer> measureIndexByKey = new HashMap<>();
        final Map<String, Integer> partCounters = new HashMap<>();

        for (NoteMapping.MeasureInfo measure : measures) {
            final int nextIndex = partCounters.getOrDefault(measure.partId, 0);
            partCounters.put(measure.partId, nextIndex + 1);
            measureIndexByKey.put(measureKey(measure), nextIndex);
        }

        return measureIndexByKey;
    }

    private static Map<String, MeasureState> buildMeasureStates (
            List<NoteMapping.MeasureInfo> measures,
            Map<String, Integer> measureIndexByKey,
            Map<Integer, NoteMapping.SheetInfo> sheetByNumber)
    {
        final Map<String, MeasureState> result = new LinkedHashMap<>();

        for (NoteMapping.MeasureInfo measure : measures) {
            final NoteMapping.SheetInfo sheet = sheetByNumber.get(measure.sheetNumber);
            if (sheet == null) {
                continue;
            }

            final String key = measureKey(measure);
            final int measureIndex = measureIndexByKey.getOrDefault(key, 0);
            final String systemId = systemId(measure.sheetNumber, measure.systemIndex);
            final String measureId = measureId(measure.sheetNumber, measure.systemIndex, measure.partId, measureIndex);

            result.put(key, new MeasureState(
                    key,
                    measureId,
                    systemId,
                    measure.partId,
                    measure.measureNumber,
                    measure.sheetNumber,
                    measure.systemIndex,
                    measureIndex,
                    measure.cumulativeTimeOffset,
                    measure.physicalMeasureIndex != null ? measure.physicalMeasureIndex : measureIndex,
                    measure.measureStackId != null ? measure.measureStackId : measure.measureNumber,
                    measure.measureStackScoreId != null ? measure.measureStackScoreId : measure.measureNumber,
                    measure.systemId,
                    measure.systemOrderInPage != null ? measure.systemOrderInPage : measure.systemIndex,
                    measure.staffIds,
                    normalize(measure.bounds, sheet.imageWidth, sheet.imageHeight),
                    measure.bounds,
                    new ArrayList<>()));
        }

        return result;
    }

    private static List<NoteState> buildNoteStates (List<NoteMapping.NoteEntry> notes,
                                                    Map<String, MeasureState> measureStateByKey,
                                                    Map<Integer, NoteMapping.SheetInfo> sheetByNumber,
                                                    BindingDiagnostics diagnostics)
    {
        final List<NoteState> result = new ArrayList<>();
        final Map<String, Integer> occurrenceCounters = new HashMap<>();
        final Map<String, Integer> measureNoteOrdinalCounters = new HashMap<>();

        for (NoteMapping.NoteEntry note : notes) {
            final MeasureState measure = measureStateByKey.get(measureKey(note));
            final NoteMapping.SheetInfo sheet = sheetByNumber.get(note.sheetNumber);

            if (measure == null) {
                diagnostics.rejects.add(BindingReject.forNote("measure-unresolved", note));
                continue;
            }

            if (sheet == null) {
                diagnostics.rejects.add(BindingReject.forNote("measure-unresolved", note));
                continue;
            }

            final Integer voice = parseInteger(note.voice);
            final Integer midiPitch = note.isRest ? null : computeMidiPitch(note);
            final int absoluteStartDiv = note.measureCumulativeTimeOffset + note.timeOffset;
            final String baseKey = note.partId + "|" + note.voice + "|" + absoluteStartDiv + "|" +
                    note.duration + "|" + (midiPitch != null ? midiPitch : "rest");
            final int occurrence = occurrenceCounters.getOrDefault(baseKey, 0);
            occurrenceCounters.put(baseKey, occurrence + 1);

            final NormalizedRect bbox = normalize(note.chordBounds, sheet.imageWidth, sheet.imageHeight);
            final NormalizedRect noteHeadBox = normalize(note.bounds, sheet.imageWidth, sheet.imageHeight);
            final String noteId = "note-" + note.globalNoteIndex;
            final int xmlNoteOrdinalInMeasure = measureNoteOrdinalCounters.getOrDefault(measure.key, 0);
            measureNoteOrdinalCounters.put(measure.key, xmlNoteOrdinalInMeasure + 1);

            final NoteState state = new NoteState(
                    noteId,
                    note.globalNoteIndex,
                    note.noteIndex,
                    xmlNoteOrdinalInMeasure,
                    note.partId,
                    note.measureNumber,
                    measure.measureIndex,
                    note.staff,
                    note.sheetNumber,
                    note.systemIndex,
                    measure.measureId,
                    measure.systemId,
                    measure.physicalMeasureIndex,
                    measure.measureStackId,
                    measure.measureStackScoreId,
                    measure.physicalSystemId,
                    measure.systemOrderInPage,
                    measure.staffIds,
                    voice,
                    note.voice,
                    absoluteStartDiv,
                    note.duration,
                    midiPitch,
                    occurrence,
                    note.noteIndexInChord,
                    note.isRest,
                    note.isGrace,
                    note.isMeasureRest,
                    note.isTiedStart,
                    note.isTiedStop,
                    bbox,
                    noteHeadBox,
                    note.chordBounds,
                    note.bounds,
                    note.noteInterId,
                    note.chordInterId,
                    note.staffId,
                    note.systemId,
                    note.glyphId,
                    Math.round(note.timeOffsetSeconds * 1000.0),
                    Math.round(note.durationSeconds * 1000.0));

            result.add(state);
            measure.noteIds.add(noteId);
        }

        return result;
    }

    private static Map<String, Integer> buildPlayableOrdinalByNoteId (List<NoteState> notes)
    {
        final List<NoteState> playableInXmlOrder = new ArrayList<>();
        for (NoteState note : notes) {
            if (isPlayable(note)) {
                playableInXmlOrder.add(note);
            }
        }

        playableInXmlOrder.sort(Comparator.comparingInt(note -> note.globalNoteIndex));

        final Map<String, Integer> nextOrdinalByPart = new HashMap<>();
        final Map<String, Integer> playableOrdinalByNoteId = new HashMap<>();
        for (NoteState note : playableInXmlOrder) {
            final int nextOrdinal = nextOrdinalByPart.getOrDefault(note.partId, 0);
            playableOrdinalByNoteId.put(note.noteId, nextOrdinal);
            nextOrdinalByPart.put(note.partId, nextOrdinal + 1);
        }

        return playableOrdinalByNoteId;
    }

    private static Integer computeMidiPitch (NoteMapping.NoteEntry note)
    {
        if ((note == null) || (note.step == null)) {
            return null;
        }

        final int pitchClass = switch (note.step.toUpperCase(Locale.ROOT)) {
            case "C" -> 0;
            case "D" -> 2;
            case "E" -> 4;
            case "F" -> 5;
            case "G" -> 7;
            case "A" -> 9;
            case "B" -> 11;
            default -> -1;
        };

        if (pitchClass < 0) {
            return null;
        }

        return ((note.octave + 1) * 12) + pitchClass + note.alter;
    }

    private static List<SystemState> buildSystemStates (int pageNumber,
                                                        List<NoteMapping.SystemInfo> systems,
                                                        NoteMapping.SheetInfo sheet,
                                                        Map<String, MeasureState> measureStateByKey,
                                                        List<NoteState> noteStates)
    {
        final List<SystemState> result = new ArrayList<>();

        for (NoteMapping.SystemInfo system : systems) {
            if (system.sheetNumber != pageNumber) {
                continue;
            }

            final String systemId = systemId(system.sheetNumber, system.systemIndex);
            final List<MeasureState> systemMeasures = new ArrayList<>();

            for (MeasureState measure : measureStateByKey.values()) {
                if ((measure.sheetNumber == pageNumber) && (measure.systemIndex == system.systemIndex)) {
                    systemMeasures.add(measure);
                }
            }

            final NoteMapping.BoundsInfo sourceBounds = !systemMeasures.isEmpty()
                    ? unionBounds(systemMeasures)
                    : system.bounds;
            final SystemState state = new SystemState(
                    systemId,
                    system.systemIndex,
                    normalize(sourceBounds, sheet.imageWidth, sheet.imageHeight),
                    new ArrayList<>(),
                    new ArrayList<>());

            for (MeasureState measure : systemMeasures) {
                state.measureIds.add(measure.measureId);
            }

            for (NoteState note : noteStates) {
                if ((note.sheetNumber == pageNumber) && (note.systemIndex == system.systemIndex)) {
                    state.noteIds.add(note.noteId);
                }
            }

            result.add(state);
        }

        result.sort(Comparator.comparingInt(system -> system.orderInPage));

        return result;
    }

    private static NoteMapping.BoundsInfo unionBounds (List<MeasureState> measures)
    {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;

        for (MeasureState measure : measures) {
            final NoteMapping.BoundsInfo bounds = measure.sourceBBox;
            left = Math.min(left, bounds.x);
            top = Math.min(top, bounds.y);
            right = Math.max(right, bounds.x + bounds.width);
            bottom = Math.max(bottom, bounds.y + bounds.height);
        }

        if ((left == Integer.MAX_VALUE) || (top == Integer.MAX_VALUE)) {
            return new NoteMapping.BoundsInfo(0, 0, 0, 0);
        }

        return new NoteMapping.BoundsInfo(left, top, right - left, bottom - top);
    }

    private static List<MeasureState> buildPageMeasures (int pageNumber,
                                                         Collection<MeasureState> measures)
    {
        final List<MeasureState> result = new ArrayList<>();

        for (MeasureState measure : measures) {
            if (measure.sheetNumber == pageNumber) {
                result.add(measure);
            }
        }

        result.sort(Comparator
                .comparingInt((MeasureState measure) -> measure.systemIndex)
                .thenComparingInt(measure -> measure.measureIndex)
                .thenComparing(measure -> measure.partId));

        return result;
    }

    private static List<NoteState> buildPageNotes (int pageNumber,
                                                   List<NoteState> notes)
    {
        final List<NoteState> result = new ArrayList<>();

        for (NoteState note : notes) {
            if (note.sheetNumber == pageNumber) {
                result.add(note);
            }
        }

        result.sort(Comparator.comparingInt(note -> note.globalNoteIndex));

        return result;
    }

    private static void appendSystems (StringBuilder sb,
                                       List<SystemState> systems,
                                       String indent)
    {
        sb.append(indent).append("\"systems\": [\n");

        for (int index = 0; index < systems.size(); index++) {
            final SystemState system = systems.get(index);
            sb.append(indent).append("  {\n");
            sb.append(indent).append("    \"id\": ").append(jsonString(system.systemId)).append(",\n");
            sb.append(indent).append("    \"bbox\": ").append(system.bbox.toJson()).append(",\n");
            sb.append(indent).append("    \"orderInPage\": ").append(system.orderInPage).append(",\n");
            sb.append(indent).append("    \"measureIds\": ").append(stringArray(system.measureIds)).append(",\n");
            sb.append(indent).append("    \"noteIds\": ").append(stringArray(system.noteIds)).append("\n");
            sb.append(indent).append("  }");
            if (index < systems.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append(indent).append("]");
    }

    private static void appendMeasures (StringBuilder sb,
                                        List<MeasureState> measures,
                                        String indent)
    {
        sb.append(indent).append("\"measures\": [\n");

        for (int index = 0; index < measures.size(); index++) {
            final MeasureState measure = measures.get(index);
            sb.append(indent).append("  {\n");
            sb.append(indent).append("    \"id\": ").append(jsonString(measure.measureId)).append(",\n");
            sb.append(indent).append("    \"bbox\": ").append(measure.bbox.toJson()).append(",\n");
            sb.append(indent).append("    \"pageIndex\": ").append(measure.sheetNumber - 1).append(",\n");
            sb.append(indent).append("    \"systemId\": ").append(jsonString(measure.systemId)).append(",\n");
            sb.append(indent).append("    \"partId\": ").append(jsonString(measure.partId)).append(",\n");
            sb.append(indent).append("    \"measureIndex\": ").append(measure.measureIndex).append(",\n");
            sb.append(indent).append("    \"measureNumber\": ").append(jsonString(measure.measureNumber)).append(",\n");
            appendMusicXml(sb, measure, indent + "    ");
            sb.append(",\n");
            appendPhysical(sb, measure, indent + "    ");
            sb.append(",\n");
            sb.append(indent).append("    \"bindingStatus\": \"bound\",\n");
            sb.append(indent).append("    \"noteIds\": ").append(stringArray(measure.noteIds)).append(",\n");
            sb.append(indent).append("    \"sourceBBox\": ").append(toBoundsJson(measure.sourceBBox)).append("\n");
            sb.append(indent).append("  }");
            if (index < measures.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append(indent).append("]");
    }

    private static void appendNotes (StringBuilder sb,
                                     List<NoteState> notes,
                                     Map<String, Integer> playableOrdinalByNoteId,
                                     String indent)
    {
        sb.append(indent).append("\"notes\": [\n");

        for (int index = 0; index < notes.size(); index++) {
            final NoteState note = notes.get(index);
            sb.append(indent).append("  {\n");
            sb.append(indent).append("    \"id\": ").append(jsonString(note.noteId)).append(",\n");
            sb.append(indent).append("    \"bbox\": ").append(note.bbox.toJson()).append(",\n");
            sb.append(indent).append("    \"noteHeadBBox\": ").append(note.noteHeadBBox.toJson()).append(",\n");
            sb.append(indent).append("    \"pageIndex\": ").append(note.sheetNumber - 1).append(",\n");
            sb.append(indent).append("    \"systemId\": ").append(jsonString(note.systemId)).append(",\n");
            sb.append(indent).append("    \"measureId\": ").append(jsonString(note.measureId)).append(",\n");
            sb.append(indent).append("    \"semantic\": {\n");
            sb.append(indent).append("      \"partId\": ").append(jsonString(note.partId)).append(",\n");
            sb.append(indent).append("      \"voice\": ").append(note.voice != null ? note.voice : "null").append(",\n");
            sb.append(indent).append("      \"voiceRaw\": ").append(jsonString(note.voiceRaw)).append(",\n");
            sb.append(indent).append("      \"staff\": ").append(note.staff).append(",\n");
            sb.append(indent).append("      \"measureIndex\": ").append(note.measureIndex).append(",\n");
            sb.append(indent).append("      \"measureNumber\": ").append(jsonString(note.measureNumber)).append(",\n");
            sb.append(indent).append("      \"startDivision\": ").append(note.startDivision).append(",\n");
            sb.append(indent).append("      \"durationDivisions\": ").append(note.durationDivisions).append(",\n");
            sb.append(indent).append("      \"midiPitch\": ").append(note.midiPitch != null ? note.midiPitch : "null").append(",\n");
            sb.append(indent).append("      \"occurrence\": ").append(note.occurrence).append("\n");
            sb.append(indent).append("    },\n");
            appendMusicXml(sb, note, playableOrdinalByNoteId.get(note.noteId), indent + "    ");
            sb.append(",\n");
            appendPhysical(sb, note, indent + "    ");
            sb.append(",\n");
            sb.append(indent).append("    \"flags\": {\n");
            sb.append(indent).append("      \"isRest\": ").append(note.isRest).append(",\n");
            sb.append(indent).append("      \"isGrace\": ").append(note.isGrace).append(",\n");
            sb.append(indent).append("      \"isMeasureRest\": ").append(note.isMeasureRest).append(",\n");
            sb.append(indent).append("      \"isTiedStart\": ").append(note.isTiedStart).append(",\n");
            sb.append(indent).append("      \"isTiedStop\": ").append(note.isTiedStop).append("\n");
            sb.append(indent).append("    },\n");
            sb.append(indent).append("    \"timingMs\": {\n");
            sb.append(indent).append("      \"start\": ").append(note.startMs).append(",\n");
            sb.append(indent).append("      \"duration\": ").append(note.durationMs).append(",\n");
            sb.append(indent).append("      \"confidence\": \"advisory\"\n");
            sb.append(indent).append("    },\n");
            sb.append(indent).append("    \"raw\": {\n");
            sb.append(indent).append("      \"globalNoteIndex\": ").append(note.globalNoteIndex).append(",\n");
            sb.append(indent).append("      \"noteIndex\": ").append(note.noteIndex).append(",\n");
            sb.append(indent).append("      \"noteIndexInChord\": ").append(note.noteIndexInChord).append("\n");
            sb.append(indent).append("    },\n");
            appendProvenance(sb, note, indent + "    ");
            sb.append(",\n");
            sb.append(indent).append("    \"sourceBBox\": ").append(toBoundsJson(note.sourceBBox)).append(",\n");
            sb.append(indent).append("    \"sourceNoteHeadBBox\": ").append(toBoundsJson(note.sourceNoteHeadBBox)).append("\n");
            sb.append(indent).append("  }");
            if (index < notes.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append(indent).append("]");
    }

    private static void appendMusicXml (StringBuilder sb,
                                        MeasureState measure,
                                        String indent)
    {
        sb.append(indent).append("\"musicXml\": {\n");
        sb.append(indent).append("  \"xmlPartId\": ").append(jsonString(measure.partId)).append(",\n");
        sb.append(indent).append("  \"xmlMeasureIndex\": ").append(measure.measureIndex).append(",\n");
        sb.append(indent).append("  \"xmlMeasureNumber\": ").append(jsonString(measure.measureNumber)).append("\n");
        sb.append(indent).append("}");
    }

    private static void appendMusicXml (StringBuilder sb,
                                        NoteState note,
                                        Integer playableOrdinalInPart,
                                        String indent)
    {
        sb.append(indent).append("\"musicXml\": {\n");
        sb.append(indent).append("  \"xmlPartId\": ").append(jsonString(note.partId)).append(",\n");
        sb.append(indent).append("  \"xmlMeasureIndex\": ").append(note.measureIndex).append(",\n");
        sb.append(indent).append("  \"xmlMeasureNumber\": ").append(jsonString(note.measureNumber)).append(",\n");
        sb.append(indent).append("  \"xmlNoteOrdinal\": ").append(note.noteIndex).append(",\n");
        sb.append(indent).append("  \"xmlNoteOrdinalInMeasure\": ")
                .append(note.xmlNoteOrdinalInMeasure)
                .append(",\n");
        sb.append(indent).append("  \"playableOrdinalInPart\": ")
                .append(playableOrdinalInPart != null ? playableOrdinalInPart : "null")
                .append(",\n");
        sb.append(indent).append("  \"exported\": true\n");
        sb.append(indent).append("}");
    }

    private static void appendPhysical (StringBuilder sb,
                                        MeasureState measure,
                                        String indent)
    {
        sb.append(indent).append("\"physical\": {\n");
        sb.append(indent).append("  \"physicalMeasureIndex\": ").append(measure.physicalMeasureIndex).append(",\n");
        sb.append(indent).append("  \"measureStackId\": ").append(jsonString(measure.measureStackId)).append(",\n");
        sb.append(indent).append("  \"measureStackScoreId\": ").append(jsonString(measure.measureStackScoreId)).append(",\n");
        sb.append(indent).append("  \"pageIndex\": ").append(measure.sheetNumber - 1).append(",\n");
        sb.append(indent).append("  \"pageNumber\": ").append(measure.sheetNumber).append(",\n");
        sb.append(indent).append("  \"systemId\": ").append(numberOrNull(measure.physicalSystemId)).append(",\n");
        sb.append(indent).append("  \"systemOrderInPage\": ").append(measure.systemOrderInPage).append(",\n");
        sb.append(indent).append("  \"partId\": ").append(jsonString(measure.partId)).append(",\n");
        sb.append(indent).append("  \"staffIds\": ").append(integerArray(measure.staffIds)).append("\n");
        sb.append(indent).append("}");
    }

    private static void appendPhysical (StringBuilder sb,
                                        NoteState note,
                                        String indent)
    {
        final Integer systemId = (note.physicalSystemId != null) ? note.physicalSystemId : note.provenanceSystemId;

        sb.append(indent).append("\"physical\": {\n");
        sb.append(indent).append("  \"physicalMeasureIndex\": ").append(note.physicalMeasureIndex).append(",\n");
        sb.append(indent).append("  \"measureStackId\": ").append(jsonString(note.measureStackId)).append(",\n");
        sb.append(indent).append("  \"measureStackScoreId\": ").append(jsonString(note.measureStackScoreId)).append(",\n");
        sb.append(indent).append("  \"pageIndex\": ").append(note.sheetNumber - 1).append(",\n");
        sb.append(indent).append("  \"systemId\": ").append(numberOrNull(systemId)).append(",\n");
        sb.append(indent).append("  \"partId\": ").append(jsonString(note.partId)).append(",\n");
        sb.append(indent).append("  \"staffId\": ").append(numberOrNull(note.staffId)).append("\n");
        sb.append(indent).append("}");
    }

    private static void appendPlayback (StringBuilder sb,
                                        List<NoteState> notes,
                                        Map<String, Integer> playableOrdinalByNoteId,
                                        String indent)
    {
        sb.append(indent).append("\"playback\": {\n");
        sb.append(indent).append("  \"primaryMatch\": \"semantic\",\n");
        sb.append(indent).append("  \"advisoryTiming\": true,\n");
        sb.append(indent).append("  \"noteRefs\": [\n");

        final List<NoteState> playableInPlaybackOrder = new ArrayList<>();
        for (NoteState note : notes) {
            if (isPlayable(note)) {
                playableInPlaybackOrder.add(note);
            }
        }

        playableInPlaybackOrder.sort(
                Comparator.comparingLong((NoteState note) -> note.startMs)
                        .thenComparing(note -> note.partId)
                        .thenComparingInt(GeometrySidecarExporter::voiceSortKey)
                        .thenComparingInt(note -> playableOrdinalByNoteId.getOrDefault(note.noteId, 0))
                        .thenComparingInt(note -> note.globalNoteIndex));

        for (int index = 0; index < playableInPlaybackOrder.size(); index++) {
            final NoteState note = playableInPlaybackOrder.get(index);
            final int musicXmlNoteOrdinal = playableOrdinalByNoteId.getOrDefault(note.noteId, 0);
            sb.append(indent).append("    {\n");
            sb.append(indent).append("      \"noteId\": ").append(jsonString(note.noteId)).append(",\n");
            sb.append(indent).append("      \"playbackIndex\": ").append(index).append(",\n");
            sb.append(indent).append("      \"musicXmlNoteOrdinal\": ").append(musicXmlNoteOrdinal).append(",\n");
            appendMusicXml(sb, note, musicXmlNoteOrdinal, indent + "      ");
            sb.append(",\n");
            appendPhysical(sb, note, indent + "      ");
            sb.append(",\n");
            sb.append(indent).append("      \"semantic\": {\n");
            sb.append(indent).append("        \"partId\": ").append(jsonString(note.partId)).append(",\n");
            sb.append(indent).append("        \"voice\": ").append(note.voice != null ? note.voice : "null").append(",\n");
            sb.append(indent).append("        \"voiceRaw\": ").append(jsonString(note.voiceRaw)).append(",\n");
            sb.append(indent).append("        \"staff\": ").append(note.staff).append(",\n");
            sb.append(indent).append("        \"measureIndex\": ").append(note.measureIndex).append(",\n");
            sb.append(indent).append("        \"measureNumber\": ").append(jsonString(note.measureNumber)).append(",\n");
            sb.append(indent).append("        \"startDivision\": ").append(note.startDivision).append(",\n");
            sb.append(indent).append("        \"durationDivisions\": ").append(note.durationDivisions).append(",\n");
            sb.append(indent).append("        \"midiPitch\": ").append(note.midiPitch != null ? note.midiPitch : "null").append(",\n");
            sb.append(indent).append("        \"occurrence\": ").append(note.occurrence).append("\n");
            sb.append(indent).append("      },\n");
            sb.append(indent).append("      \"timingMs\": {\n");
            sb.append(indent).append("        \"start\": ").append(note.startMs).append(",\n");
            sb.append(indent).append("        \"duration\": ").append(note.durationMs).append(",\n");
            sb.append(indent).append("        \"confidence\": \"advisory\"\n");
            sb.append(indent).append("      },\n");
            appendProvenance(sb, note, indent + "      ");
            sb.append("\n");
            sb.append(indent).append("    }");
            if (index < playableInPlaybackOrder.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append(indent).append("  ]\n");
        sb.append(indent).append("}");
    }

    private static void appendChords (StringBuilder sb,
                                      List<NoteState> notes,
                                      String indent)
    {
        final Map<Integer, List<NoteState>> notesByChord = new LinkedHashMap<>();
        for (NoteState note : notes) {
            if ((note.chordInterId != null) && !note.isRest) {
                notesByChord.computeIfAbsent(note.chordInterId, key -> new ArrayList<>()).add(note);
            }
        }

        sb.append(indent).append("\"chords\": [\n");
        int chordIndex = 0;
        for (Map.Entry<Integer, List<NoteState>> entry : notesByChord.entrySet()) {
            final List<NoteState> chordNotes = entry.getValue();
            chordNotes.sort(Comparator
                    .comparingInt((NoteState note) -> note.noteIndexInChord)
                    .thenComparingInt(note -> note.globalNoteIndex));

            final NoteState root = chordNotes.get(0);
            sb.append(indent).append("  {\n");
            sb.append(indent).append("    \"id\": ").append(jsonString("chord-" + entry.getKey())).append(",\n");
            sb.append(indent).append("    \"chordInterId\": ").append(entry.getKey()).append(",\n");
            sb.append(indent).append("    \"rootNoteInterId\": ").append(numberOrNull(root.noteInterId)).append(",\n");
            sb.append(indent).append("    \"memberNoteInterIds\": ")
                    .append(noteInterIdArray(chordNotes))
                    .append(",\n");
            sb.append(indent).append("    \"exportedNoteIds\": ")
                    .append(noteIdArray(chordNotes))
                    .append(",\n");
            sb.append(indent).append("    \"exportedXmlOrdinals\": [\n");
            for (int noteIndex = 0; noteIndex < chordNotes.size(); noteIndex++) {
                final NoteState note = chordNotes.get(noteIndex);
                sb.append(indent).append("      {\n");
                sb.append(indent).append("        \"noteId\": ").append(jsonString(note.noteId)).append(",\n");
                sb.append(indent).append("        \"xmlPartId\": ").append(jsonString(note.partId)).append(",\n");
                sb.append(indent).append("        \"xmlMeasureIndex\": ").append(note.measureIndex).append(",\n");
                sb.append(indent).append("        \"xmlNoteOrdinal\": ").append(note.noteIndex).append(",\n");
                sb.append(indent).append("        \"xmlNoteOrdinalInMeasure\": ")
                        .append(note.xmlNoteOrdinalInMeasure)
                        .append(",\n");
                sb.append(indent).append("        \"noteIndexInChord\": ")
                        .append(note.noteIndexInChord)
                        .append(",\n");
                sb.append(indent).append("        \"chordRole\": ")
                        .append(jsonString(note.noteIndexInChord == 0 ? "root" : "member"))
                        .append("\n");
                sb.append(indent).append("      }");
                if (noteIndex < chordNotes.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append(indent).append("    ],\n");
            sb.append(indent).append("    \"sameOnset\": {\n");
            sb.append(indent).append("      \"startDivision\": ").append(root.startDivision).append(",\n");
            sb.append(indent).append("      \"durationDivisions\": ").append(root.durationDivisions).append(",\n");
            sb.append(indent).append("      \"voiceRaw\": ").append(jsonString(root.voiceRaw)).append(",\n");
            sb.append(indent).append("      \"staff\": ").append(root.staff).append(",\n");
            sb.append(indent).append("      \"evidence\": \"same-chord-inter\"\n");
            sb.append(indent).append("    },\n");
            sb.append(indent).append("    \"source\": {\n");
            sb.append(indent).append("      \"partId\": ").append(jsonString(root.partId)).append(",\n");
            sb.append(indent).append("      \"staffId\": ").append(numberOrNull(root.staffId)).append(",\n");
            sb.append(indent).append("      \"systemId\": ").append(numberOrNull(root.provenanceSystemId)).append(",\n");
            sb.append(indent).append("      \"measureId\": ").append(jsonString(root.measureId)).append("\n");
            sb.append(indent).append("    }\n");
            sb.append(indent).append("  }");
            if (++chordIndex < notesByChord.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append(indent).append("]");
    }

    private static void appendBindingDiagnostics (StringBuilder sb,
                                                  BindingDiagnostics diagnostics,
                                                  String indent)
    {
        sb.append(indent).append("\"bindingDiagnostics\": {\n");
        sb.append(indent).append("  \"rejects\": [\n");
        for (int index = 0; index < diagnostics.rejects.size(); index++) {
            final BindingReject reject = diagnostics.rejects.get(index);
            sb.append(indent).append("    {\n");
            sb.append(indent).append("      \"reason\": ").append(jsonString(reject.reason)).append(",\n");
            sb.append(indent).append("      \"objectType\": ").append(jsonString(reject.objectType)).append(",\n");
            sb.append(indent).append("      \"noteInterId\": ").append(numberOrNull(reject.noteInterId)).append(",\n");
            sb.append(indent).append("      \"chordInterId\": ").append(numberOrNull(reject.chordInterId)).append(",\n");
            sb.append(indent).append("      \"staffId\": ").append(numberOrNull(reject.staffId)).append(",\n");
            sb.append(indent).append("      \"partId\": ").append(jsonString(reject.partId)).append(",\n");
            sb.append(indent).append("      \"xmlMeasureNumber\": ")
                    .append(jsonString(reject.xmlMeasureNumber))
                    .append("\n");
            sb.append(indent).append("    }");
            if (index < diagnostics.rejects.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append(indent).append("  ],\n");
        sb.append(indent).append("  \"conflicts\": []\n");
        sb.append(indent).append("}");
    }

    private static boolean isPlayable (NoteState note)
    {
        return (note != null) && !note.isRest && (note.midiPitch != null);
    }

    private static void appendProvenance (StringBuilder sb,
                                          NoteState note,
                                          String indent)
    {
        sb.append(indent).append("\"provenance\": {\n");
        sb.append(indent).append("  \"noteInterId\": ").append(numberOrNull(note.noteInterId)).append(",\n");
        sb.append(indent).append("  \"chordInterId\": ").append(numberOrNull(note.chordInterId)).append(",\n");
        sb.append(indent).append("  \"staffId\": ").append(numberOrNull(note.staffId)).append(",\n");
        sb.append(indent).append("  \"systemId\": ").append(numberOrNull(note.provenanceSystemId)).append(",\n");
        sb.append(indent).append("  \"glyphId\": ").append(numberOrNull(note.glyphId)).append("\n");
        sb.append(indent).append("}");
    }

    private static int voiceSortKey (NoteState note)
    {
        if (note == null) {
            return Integer.MAX_VALUE;
        }

        if (note.voice != null) {
            return note.voice;
        }

        final Integer parsed = parseInteger(note.voiceRaw);
        return (parsed != null) ? parsed : Integer.MAX_VALUE;
    }

    private static String systemId (int sheetNumber,
                                    int systemIndex)
    {
        return "page-" + sheetNumber + "-system-" + (systemIndex + 1);
    }

    private static String measureId (int sheetNumber,
                                     int systemIndex,
                                     String partId,
                                     int measureIndex)
    {
        return "page-" + sheetNumber + "-system-" + (systemIndex + 1) + "-part-" +
                sanitize(partId) + "-measure-" + (measureIndex + 1);
    }

    private static String measureKey (NoteMapping.MeasureInfo measure)
    {
        return measure.partId + "|" + measure.measureNumber + "|" + measure.sheetNumber + "|" +
                measure.systemIndex + "|" + measure.cumulativeTimeOffset;
    }

    private static String measureKey (NoteMapping.NoteEntry note)
    {
        return note.partId + "|" + note.measureNumber + "|" + note.sheetNumber + "|" +
                note.systemIndex + "|" + note.measureCumulativeTimeOffset;
    }

    private static Integer parseInteger (String value)
    {
        try {
            return Integer.parseInt(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String sanitize (String value)
    {
        if (value == null) {
            return "unknown";
        }

        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static NormalizedRect normalize (NoteMapping.BoundsInfo bounds,
                                             int imageWidth,
                                             int imageHeight)
    {
        final double width = Math.max(1.0, imageWidth);
        final double height = Math.max(1.0, imageHeight);

        return new NormalizedRect(
                clamp(bounds.x / width),
                clamp(bounds.y / height),
                clamp(bounds.width / width),
                clamp(bounds.height / height));
    }

    private static double clamp (double value)
    {
        if (value < 0) {
            return 0;
        }

        if (value > 1) {
            return 1;
        }

        return value;
    }

    private static String stringArray (List<String> values)
    {
        final StringBuilder sb = new StringBuilder("[");

        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                sb.append(", ");
            }

            sb.append(jsonString(values.get(index)));
        }

        sb.append("]");
        return sb.toString();
    }

    private static String integerArray (List<Integer> values)
    {
        final StringBuilder sb = new StringBuilder("[");

        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                sb.append(", ");
            }

            sb.append(values.get(index));
        }

        sb.append("]");
        return sb.toString();
    }

    private static String noteInterIdArray (List<NoteState> notes)
    {
        final List<Integer> values = new ArrayList<>();
        for (NoteState note : notes) {
            if (note.noteInterId != null) {
                values.add(note.noteInterId);
            }
        }

        return integerArray(values);
    }

    private static String noteIdArray (List<NoteState> notes)
    {
        final List<String> values = new ArrayList<>();
        for (NoteState note : notes) {
            values.add(note.noteId);
        }

        return stringArray(values);
    }

    private static String toBoundsJson (NoteMapping.BoundsInfo bounds)
    {
        return "{\"x\": " + bounds.x + ", \"y\": " + bounds.y + ", \"width\": " +
                bounds.width + ", \"height\": " + bounds.height + "}";
    }

    private static String jsonString (String value)
    {
        if (value == null) {
            return "null";
        }

        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }

    private static String numberOrNull (Integer value)
    {
        return (value != null) ? value.toString() : "null";
    }

    private static String number (double value)
    {
        final String raw = String.format(Locale.US, "%.6f", value);
        int end = raw.length();

        while ((end > 0) && (raw.charAt(end - 1) == '0')) {
            end--;
        }

        if ((end > 0) && (raw.charAt(end - 1) == '.')) {
            end--;
        }

        return (end > 0) ? raw.substring(0, end) : "0";
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    private static final class SourcePageInfo
    {
        final String kind;
        final double width;
        final double height;
        final String unit;
        final Integer rotationDegrees;

        SourcePageInfo (String kind,
                        double width,
                        double height,
                        String unit,
                        Integer rotationDegrees)
        {
            this.kind = kind;
            this.width = width;
            this.height = height;
            this.unit = unit;
            this.rotationDegrees = rotationDegrees;
        }
    }

    private static final class SystemState
    {
        final String systemId;
        final int orderInPage;
        final NormalizedRect bbox;
        final List<String> measureIds;
        final List<String> noteIds;

        SystemState (String systemId,
                     int orderInPage,
                     NormalizedRect bbox,
                     List<String> measureIds,
                     List<String> noteIds)
        {
            this.systemId = systemId;
            this.orderInPage = orderInPage;
            this.bbox = bbox;
            this.measureIds = measureIds;
            this.noteIds = noteIds;
        }
    }

    private static final class MeasureState
    {
        final String key;
        final String measureId;
        final String systemId;
        final String partId;
        final String measureNumber;
        final int sheetNumber;
        final int systemIndex;
        final int measureIndex;
        final int cumulativeTimeOffset;
        final int physicalMeasureIndex;
        final String measureStackId;
        final String measureStackScoreId;
        final Integer physicalSystemId;
        final int systemOrderInPage;
        final List<Integer> staffIds;
        final NormalizedRect bbox;
        final NoteMapping.BoundsInfo sourceBBox;
        final List<String> noteIds;

        MeasureState (String key,
                      String measureId,
                      String systemId,
                      String partId,
                      String measureNumber,
                      int sheetNumber,
                      int systemIndex,
                      int measureIndex,
                      int cumulativeTimeOffset,
                      int physicalMeasureIndex,
                      String measureStackId,
                      String measureStackScoreId,
                      Integer physicalSystemId,
                      int systemOrderInPage,
                      List<Integer> staffIds,
                      NormalizedRect bbox,
                      NoteMapping.BoundsInfo sourceBBox,
                      List<String> noteIds)
        {
            this.key = key;
            this.measureId = measureId;
            this.systemId = systemId;
            this.partId = partId;
            this.measureNumber = measureNumber;
            this.sheetNumber = sheetNumber;
            this.systemIndex = systemIndex;
            this.measureIndex = measureIndex;
            this.cumulativeTimeOffset = cumulativeTimeOffset;
            this.physicalMeasureIndex = physicalMeasureIndex;
            this.measureStackId = measureStackId;
            this.measureStackScoreId = measureStackScoreId;
            this.physicalSystemId = physicalSystemId;
            this.systemOrderInPage = systemOrderInPage;
            this.staffIds = staffIds;
            this.bbox = bbox;
            this.sourceBBox = sourceBBox;
            this.noteIds = noteIds;
        }
    }

    private static final class NoteState
    {
        final String noteId;
        final int globalNoteIndex;
        final int noteIndex;
        final int xmlNoteOrdinalInMeasure;
        final String partId;
        final String measureNumber;
        final int measureIndex;
        final int staff;
        final int sheetNumber;
        final int systemIndex;
        final String measureId;
        final String systemId;
        final int physicalMeasureIndex;
        final String measureStackId;
        final String measureStackScoreId;
        final Integer physicalSystemId;
        final int systemOrderInPage;
        final List<Integer> staffIds;
        final Integer voice;
        final String voiceRaw;
        final int startDivision;
        final int durationDivisions;
        final Integer midiPitch;
        final int occurrence;
        final int noteIndexInChord;
        final boolean isRest;
        final boolean isGrace;
        final boolean isMeasureRest;
        final boolean isTiedStart;
        final boolean isTiedStop;
        final NormalizedRect bbox;
        final NormalizedRect noteHeadBBox;
        final NoteMapping.BoundsInfo sourceBBox;
        final NoteMapping.BoundsInfo sourceNoteHeadBBox;
        final Integer noteInterId;
        final Integer chordInterId;
        final Integer staffId;
        final Integer provenanceSystemId;
        final Integer glyphId;
        final long startMs;
        final long durationMs;

        NoteState (String noteId,
                   int globalNoteIndex,
                   int noteIndex,
                   int xmlNoteOrdinalInMeasure,
                   String partId,
                   String measureNumber,
                   int measureIndex,
                   int staff,
                   int sheetNumber,
                   int systemIndex,
                   String measureId,
                   String systemId,
                   int physicalMeasureIndex,
                   String measureStackId,
                   String measureStackScoreId,
                   Integer physicalSystemId,
                   int systemOrderInPage,
                   List<Integer> staffIds,
                   Integer voice,
                   String voiceRaw,
                   int startDivision,
                   int durationDivisions,
                   Integer midiPitch,
                   int occurrence,
                   int noteIndexInChord,
                   boolean isRest,
                   boolean isGrace,
                   boolean isMeasureRest,
                   boolean isTiedStart,
                   boolean isTiedStop,
                   NormalizedRect bbox,
                   NormalizedRect noteHeadBBox,
                   NoteMapping.BoundsInfo sourceBBox,
                   NoteMapping.BoundsInfo sourceNoteHeadBBox,
                   Integer noteInterId,
                   Integer chordInterId,
                   Integer staffId,
                   Integer provenanceSystemId,
                   Integer glyphId,
                   long startMs,
                   long durationMs)
        {
            this.noteId = noteId;
            this.globalNoteIndex = globalNoteIndex;
            this.noteIndex = noteIndex;
            this.xmlNoteOrdinalInMeasure = xmlNoteOrdinalInMeasure;
            this.partId = partId;
            this.measureNumber = measureNumber;
            this.measureIndex = measureIndex;
            this.staff = staff;
            this.sheetNumber = sheetNumber;
            this.systemIndex = systemIndex;
            this.measureId = measureId;
            this.systemId = systemId;
            this.physicalMeasureIndex = physicalMeasureIndex;
            this.measureStackId = measureStackId;
            this.measureStackScoreId = measureStackScoreId;
            this.physicalSystemId = physicalSystemId;
            this.systemOrderInPage = systemOrderInPage;
            this.staffIds = staffIds;
            this.voice = voice;
            this.voiceRaw = voiceRaw;
            this.startDivision = startDivision;
            this.durationDivisions = durationDivisions;
            this.midiPitch = midiPitch;
            this.occurrence = occurrence;
            this.noteIndexInChord = noteIndexInChord;
            this.isRest = isRest;
            this.isGrace = isGrace;
            this.isMeasureRest = isMeasureRest;
            this.isTiedStart = isTiedStart;
            this.isTiedStop = isTiedStop;
            this.bbox = bbox;
            this.noteHeadBBox = noteHeadBBox;
            this.sourceBBox = sourceBBox;
            this.sourceNoteHeadBBox = sourceNoteHeadBBox;
            this.noteInterId = noteInterId;
            this.chordInterId = chordInterId;
            this.staffId = staffId;
            this.provenanceSystemId = provenanceSystemId;
            this.glyphId = glyphId;
            this.startMs = startMs;
            this.durationMs = durationMs;
        }
    }

    private static final class BindingDiagnostics
    {
        final List<BindingReject> rejects = new ArrayList<>();
    }

    private static final class BindingReject
    {
        final String reason;
        final String objectType;
        final Integer noteInterId;
        final Integer chordInterId;
        final Integer staffId;
        final String partId;
        final String xmlMeasureNumber;

        private BindingReject (String reason,
                               String objectType,
                               Integer noteInterId,
                               Integer chordInterId,
                               Integer staffId,
                               String partId,
                               String xmlMeasureNumber)
        {
            this.reason = reason;
            this.objectType = objectType;
            this.noteInterId = noteInterId;
            this.chordInterId = chordInterId;
            this.staffId = staffId;
            this.partId = partId;
            this.xmlMeasureNumber = xmlMeasureNumber;
        }

        static BindingReject forNote (String reason,
                                      NoteMapping.NoteEntry note)
        {
            return new BindingReject(
                    reason,
                    "note",
                    note.noteInterId,
                    note.chordInterId,
                    note.staffId,
                    note.partId,
                    note.measureNumber);
        }
    }

    private static final class NormalizedRect
    {
        final double x;
        final double y;
        final double width;
        final double height;

        NormalizedRect (double x,
                        double y,
                        double width,
                        double height)
        {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        String toJson ()
        {
            return "{\"x\": " + number(x) + ", \"y\": " + number(y) + ", \"width\": " +
                    number(width) + ", \"height\": " + number(height) + "}";
        }
    }
}
