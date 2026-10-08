package com.plotfarms.voicecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {

    private fun record(text: String): CameraAction.Record {
        val command = CommandParser.parse(text)
        assertNotNull("not understood: \"$text\"", command)
        val action = command!!.action
        assertEquals("wrong action for \"$text\"", CameraAction.Record::class, action!!::class)
        return action as CameraAction.Record
    }

    private fun assertRecord(text: String, delay: Int, duration: Int) {
        assertEquals("\"$text\"", CameraAction.Record(delay, duration), record(text))
    }

    // ---- the sentence from the requirement

    @Test fun exampleFromTheRequirement() = assertRecord("record video after 5 second till 30 seconds", 5, 30)
    @Test fun sameWithPlural() = assertRecord("Record video after 5 seconds till 30 seconds", 5, 30)

    // ---- timing phrases

    @Test fun afterAndFor() = assertRecord("start recording after 5 seconds for 30 seconds", 5, 30)
    @Test fun untilWord() = assertRecord("record after 5 seconds until 30 seconds", 5, 30)
    @Test fun abbreviations() = assertRecord("record video after 5 sec till 30 sec", 5, 30)
    @Test fun glued() = assertRecord("record after 5s for 30s", 5, 30)
    @Test fun spokenNumbers() = assertRecord("record video after five seconds for thirty seconds", 5, 30)
    @Test fun spokenCompound() = assertRecord("record after twenty five seconds for forty five seconds", 25, 45)
    @Test fun minutes() = assertRecord("record after 1 minute for 2 minutes", 60, 120)
    @Test fun halfAMinute() = assertRecord("record half a minute video after 5 seconds", 5, 30)
    @Test fun stopAfter() = assertRecord("start recording after 5 seconds and stop after 30 seconds", 5, 30)
    @Test fun stopAfterWithoutDelay() = assertRecord("start recording and stop after 30 seconds", 0, 30)
    @Test fun inSeconds() = assertRecord("record in 10 seconds for 20 seconds", 10, 20)
    @Test fun durationFirst() = assertRecord("record for 20 seconds after 10 seconds", 10, 20)

    // ---- things the old parser got wrong or that need defaults

    @Test fun durationOnlyIsNotADelay() = assertRecord("record for 30 seconds", 0, 30)
    @Test fun clipOf() = assertRecord("record a 45 second video", 0, 45)
    @Test fun longClip() = assertRecord("record a video 20 seconds long after 3 seconds", 3, 20)
    @Test fun delayOnlyUsesDefaultLength() = assertRecord("record after 5 seconds", 5, 30)
    @Test fun noNumbersUsesDefaults() = assertRecord("record video", 0, 30)
    @Test fun limitsAreEnforced() {
        assertRecord("record after 9999 seconds for 9999 seconds", 300, 600)
        assertRecord("record for 0 seconds", 0, 1)
    }

    // ---- camera choice

    @Test fun frontCameraOnly() {
        assertEquals(VoiceCommand(CameraChoice.FRONT, null), CommandParser.parse("use the front camera"))
    }

    @Test fun backCameraOnly() {
        assertEquals(VoiceCommand(CameraChoice.BACK, null), CommandParser.parse("back camera"))
        assertEquals(VoiceCommand(CameraChoice.BACK, null), CommandParser.parse("switch to the rear camera"))
    }

    @Test fun selfieMeansFront() {
        assertEquals(VoiceCommand(CameraChoice.FRONT, null), CommandParser.parse("selfie camera"))
    }

    @Test fun switchWithoutNamingACamera() {
        assertEquals(VoiceCommand(CameraChoice.TOGGLE, null), CommandParser.parse("switch camera"))
        assertEquals(VoiceCommand(CameraChoice.TOGGLE, null), CommandParser.parse("flip the camera"))
    }

    @Test fun lastNamedCameraWins() {
        assertEquals(VoiceCommand(CameraChoice.FRONT, null), CommandParser.parse("switch from back to front camera"))
        assertEquals(VoiceCommand(CameraChoice.BACK, null), CommandParser.parse("switch from front to back camera"))
    }

    @Test fun cameraAndRecordInOneSentence() {
        assertEquals(
            VoiceCommand(CameraChoice.FRONT, CameraAction.Record(5, 30)),
            CommandParser.parse("use front camera and record video after 5 seconds till 30 seconds")
        )
        assertEquals(
            VoiceCommand(CameraChoice.BACK, CameraAction.Record(0, 10)),
            CommandParser.parse("back camera record for 10 seconds")
        )
    }

    // ---- photos

    @Test fun photoWithDelay() {
        assertEquals(VoiceCommand(null, CameraAction.Photo(3)), CommandParser.parse("take a photo after 3 seconds"))
        assertEquals(VoiceCommand(null, CameraAction.Photo(0)), CommandParser.parse("take a picture"))
    }

    // ---- stopping

    @Test fun stopWords() {
        assertEquals(VoiceCommand(null, CameraAction.Stop), CommandParser.parse("stop"))
        assertEquals(VoiceCommand(null, CameraAction.Stop), CommandParser.parse("stop recording"))
        assertEquals(VoiceCommand(null, CameraAction.Stop), CommandParser.parse("cancel"))
        assertEquals(VoiceCommand(null, CameraAction.Stop), CommandParser.parse("Stop the recording!"))
    }

    // ---- zoom

    private fun zoom(text: String): ZoomChange? = CommandParser.parse(text)?.zoom

    @Test fun zoomInAndOut() {
        assertEquals(ZoomChange.In, zoom("zoom in"))
        assertEquals(ZoomChange.Out, zoom("zoom out"))
        assertEquals(ZoomChange.In, zoom("zoom"))
        assertEquals(ZoomChange.Out, zoom("zoom back"))
        assertEquals(ZoomChange.Out, zoom("wider zoom"))
    }

    @Test fun zoomToAnExactAmount() {
        assertEquals(ZoomChange.To(3f), zoom("zoom to 3"))
        assertEquals(ZoomChange.To(3f), zoom("zoom 3x"))
        assertEquals(ZoomChange.To(2.5f), zoom("zoom 2.5x"))
        assertEquals(ZoomChange.To(2f), zoom("2x zoom"))
        assertEquals(ZoomChange.To(3f), zoom("zoom three times"))
        assertEquals(ZoomChange.To(4f), zoom("zoom in to four x"))
    }

    @Test fun zoomMaximumAndReset() {
        assertEquals(ZoomChange.Max, zoom("maximum zoom"))
        assertEquals(ZoomChange.Max, zoom("zoom max"))
        assertEquals(ZoomChange.Reset, zoom("reset zoom"))
        assertEquals(ZoomChange.Reset, zoom("normal zoom"))
    }

    @Test fun zoomOnlyCommandHasNoAction() {
        assertEquals(VoiceCommand(null, null, ZoomChange.In), CommandParser.parse("zoom in"))
    }

    @Test fun zoomCombinedWithRecording() {
        assertEquals(
            VoiceCommand(null, CameraAction.Record(0, 10), ZoomChange.In),
            CommandParser.parse("zoom in and record for 10 seconds")
        )
        assertEquals(
            VoiceCommand(null, CameraAction.Record(5, 30), ZoomChange.To(2f)),
            CommandParser.parse("zoom to 2 and record video after 5 seconds till 30 seconds")
        )
        assertEquals(
            VoiceCommand(CameraChoice.FRONT, CameraAction.Record(5, 30), ZoomChange.To(1.5f)),
            CommandParser.parse("front camera zoom 1.5x record after 5 seconds for 30 seconds")
        )
    }

    @Test fun zoomNumberIsNeverATime() {
        // "to 3" and "3 times" must not become a 3-second recording length or delay
        assertEquals(CameraAction.Record(0, 30), CommandParser.parse("zoom to 3 and record")?.action)
        assertEquals(CameraAction.Record(2, 30), CommandParser.parse("zoom 3 times record after 2 seconds")?.action)
    }

    @Test fun zoomInFollowedByATimeIsStillAZoomIn() {
        assertEquals(ZoomChange.In, zoom("zoom in and record after 5 seconds"))
        assertEquals(CameraAction.Record(5, 30), CommandParser.parse("zoom in and record after 5 seconds")?.action)
    }

    @Test fun decimalsDoNotBreakTimes() {
        assertEquals(CameraAction.Record(0, 2), CommandParser.parse("record for 2.5 seconds")?.action)
    }

    // ---- configurable defaults

    private val mine = CommandDefaults(delaySeconds = 5, durationSeconds = 45)

    private fun action(text: String, defaults: CommandDefaults = mine) = CommandParser.parse(text, defaults)?.action

    @Test fun plainRecordUsesTheSavedDefaults() {
        assertEquals(CameraAction.Record(5, 45), action("record video"))
        assertEquals(CameraAction.Record(5, 45), action("start recording"))
    }

    @Test fun plainPhotoUsesTheSavedWait() {
        assertEquals(CameraAction.Photo(5), action("take a photo"))
    }

    @Test fun spokenNumbersBeatTheDefaults() {
        assertEquals(CameraAction.Record(2, 10), action("record after 2 seconds for 10 seconds"))
        assertEquals(CameraAction.Photo(1), action("take a photo after 1 second"))
        assertEquals(CameraAction.Record(5, 10), action("record for 10 seconds"))       // only the length was said: default wait
        assertEquals(CameraAction.Record(2, 45), action("record after 2 seconds"))      // only the wait was said: default length
    }

    @Test fun explicitZeroWaitIsRespected() {
        assertEquals(CameraAction.Record(0, 45), action("record after 0 seconds"))
    }

    @Test fun nowOrNoWaitSkipsTheDefaultWait() {
        assertEquals(CameraAction.Record(0, 45), action("record now"))
        assertEquals(CameraAction.Record(0, 20), action("record video immediately for 20 seconds"))
        assertEquals(CameraAction.Record(0, 45), action("start recording with no wait"))
        assertEquals(CameraAction.Photo(0), action("take a photo right away"))
    }

    @Test fun defaultsStartAtWaitZeroAndThirtySeconds() {
        assertEquals(CameraAction.Record(0, 30), CommandParser.parse("record video")?.action)
    }

    @Test fun changeDefaultsByVoice() {
        fun change(text: String) = CommandParser.parse(text)?.newDefaults
        assertEquals(DefaultsChange(5, null), change("set default wait to 5 seconds"))
        assertEquals(DefaultsChange(null, 45), change("set the default record time to 45 seconds"))
        assertEquals(DefaultsChange(null, 60), change("default recording length one minute"))
        assertEquals(DefaultsChange(3, 20), change("set default wait to 3 seconds and default record time to 20 seconds"))
        assertEquals(DefaultsChange(0, null), change("no wait by default"))
        assertEquals(DefaultsChange(10, null), change("change the default countdown to ten seconds"))
    }

    @Test fun changingDefaultsIsNotARecordingCommand() {
        val command = CommandParser.parse("set default record time to 45 seconds")!!
        assertEquals(null, command.action)
    }

    @Test fun defaultWordDoesNotBreakOtherCommands() {
        assertEquals(ZoomChange.Reset, CommandParser.parse("reset zoom to default")?.zoom)
        assertEquals(null, CommandParser.parse("reset zoom to default")?.newDefaults)
    }

    // ---- reviewing recorded videos

    @Test fun reviewCommands() {
        val review = VoiceCommand(null, null, null, review = true)
        assertEquals(review, CommandParser.parse("play"))
        assertEquals(review, CommandParser.parse("play my last video"))
        assertEquals(review, CommandParser.parse("replay it"))
        assertEquals(review, CommandParser.parse("show my videos"))
        assertEquals(review, CommandParser.parse("show me my takes"))
        assertEquals(review, CommandParser.parse("open the gallery"))
    }

    @Test fun showingVideosIsNeverStartingARecording() {
        assertEquals(null, CommandParser.parse("show my videos")?.action)
        assertEquals(null, CommandParser.parse("view recordings")?.action)
    }

    @Test fun recordingSentencesAreNotReviews() {
        assertEquals(false, CommandParser.parse("record video after 5 seconds till 30 seconds")?.review)
        assertEquals(false, CommandParser.parse("start recording and play a tone")?.review)
    }

    // ---- not a command

    @Test fun smallTalkIsIgnored() {
        assertNull(CommandParser.parse("hello how are you today"))
        assertNull(CommandParser.parse(""))
        assertNull(CommandParser.parse("   "))
        assertNull(CommandParser.parse("what is the weather"))
    }
}
