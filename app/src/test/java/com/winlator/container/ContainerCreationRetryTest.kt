package com.winlator.container

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContainerCreationRetryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun interruptedSetup_reusesDirectoryWithoutDeletingItsFiles() {
        val directory = temporary.newFolder("xuser-wow_forever")
        val existingFile = File(directory, ".wine/user.reg").apply { parentFile.mkdirs(); writeText("partial prefix") }

        assertTrue(ContainerManager.prepareContainerDirectory(directory))
        assertTrue(existingFile.isFile)
    }

    @Test
    fun savedContainer_isNotOverwrittenByNewCreation() {
        val directory = temporary.newFolder("xuser-saved")
        val config = File(directory, ".container").apply { writeText("{\"id\":\"saved\"}") }

        assertFalse(ContainerManager.prepareContainerDirectory(directory))
        assertTrue(config.isFile)
    }

    @Test
    fun newDirectory_isCreatedAndExistingFileIsRejected() {
        assertTrue(ContainerManager.prepareContainerDirectory(File(temporary.root, "new-container")))
        assertFalse(ContainerManager.prepareContainerDirectory(temporary.newFile("not-a-directory")))
    }
}
