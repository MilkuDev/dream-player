package org.milkdev.dreamplayer.audio

import java.io.File
import java.io.InputStream
import java.util.ServiceLoader
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException
import javax.sound.sampled.spi.AudioFileReader

internal fun File.readJvmAudioFileFormat(): AudioFileFormat {
    return when {
        extension.equals("flac", ignoreCase = true) -> flacAudioFileReader().getAudioFileFormat(this)
        extension.equals("mp3", ignoreCase = true) -> mpegAudioFileReader().getAudioFileFormat(this)
        else -> AudioSystem.getAudioFileFormat(this)
    }
}

internal fun File.openFlacAudioInputStream(): AudioInputStream {
    return flacAudioFileReader().getAudioInputStream(this)
}

internal fun InputStream.openMpegAudioInputStream(): AudioInputStream {
    return mpegAudioFileReader().getAudioInputStream(this)
}

private fun flacAudioFileReader(): AudioFileReader {
    return audioFileReader(FLAC_AUDIO_FILE_READER)
}

private fun mpegAudioFileReader(): AudioFileReader {
    return audioFileReader(MPEG_AUDIO_FILE_READER)
}

private fun audioFileReader(className: String): AudioFileReader {
    return ServiceLoader.load(AudioFileReader::class.java)
        .firstOrNull { reader -> reader.javaClass.name == className }
        ?: throw UnsupportedAudioFileException("Required audio decoder is not available: $className")
}

private const val FLAC_AUDIO_FILE_READER = "com.io7m.flannel.jflac.spi.FlacAudioFileReader"
private const val MPEG_AUDIO_FILE_READER = "javazoom.spi.mpeg.sampled.file.MpegAudioFileReader"
