package dev.brikk.ducklake.corpus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** H01: a supplied root is a parent, never a directory whose existing contents we own. */
internal class TestDuckDbOracleOwnership {
    @Test fun aSuppliedParentAndItsExistingFilesSurviveClose(@TempDir root: Path) {
        val parent = Files.createDirectory(root.resolve("parent"))
        val marker = Files.writeString(parent.resolve("keep.txt"), "pre-existing")
        val oracle = DuckDbOracle(parent)
        val owned = oracle.testDir
        Files.writeString(owned.resolve("owned.txt"), "temporary")
        oracle.close()
        oracle.close() // Closing an already-cleaned child must not fall back to deleting its parent.
        assertThat(owned).isNotEqualTo(parent)
        assertThat(owned.parent).isEqualTo(parent.toAbsolutePath())
        assertThat(Files.exists(owned, NOFOLLOW_LINKS)).isFalse()
        assertThat(Files.isDirectory(parent)).isTrue()
        assertThat(Files.readString(marker)).isEqualTo("pre-existing")
    }

    @Test fun directorySymlinksDoNotDeleteTheirTargets(@TempDir root: Path) {
        val parent = Files.createDirectory(root.resolve("parent"))
        val target = Files.createDirectory(root.resolve("target"))
        val marker = Files.writeString(target.resolve("keep.txt"), "target")
        val owned = DuckDbOracle(parent).use { oracle ->
            Files.createSymbolicLink(oracle.testDir.resolve("directory-link"), target)
            oracle.testDir
        }
        assertThat(Files.isDirectory(target)).isTrue()
        assertThat(Files.exists(marker)).isTrue()
        assertThat(Files.readString(marker)).isEqualTo("target")
        assertThat(Files.exists(owned, NOFOLLOW_LINKS)).isFalse()
        assertThat(Files.isDirectory(parent)).isTrue()
    }

    @Test fun fileSymlinksAreUnlinkedWithoutDeletingTheirTargets(@TempDir root: Path) {
        val parent = Files.createDirectory(root.resolve("parent"))
        val target = Files.writeString(root.resolve("target.txt"), "target")
        val owned = DuckDbOracle(parent).use { oracle ->
            Files.createSymbolicLink(oracle.testDir.resolve("file-link"), target)
            oracle.testDir
        }
        assertThat(Files.readString(target)).isEqualTo("target")
        assertThat(Files.exists(owned, NOFOLLOW_LINKS)).isFalse()
    }

    @Test fun anOwnedRootReplacedWithASymlinkIsNotTraversed(@TempDir root: Path) {
        val parent = Files.createDirectory(root.resolve("parent"))
        val target = Files.createDirectory(root.resolve("target"))
        val marker = Files.writeString(target.resolve("keep.txt"), "target")
        val oracle = DuckDbOracle(parent)
        val owned = oracle.testDir
        Files.delete(owned)
        Files.createSymbolicLink(owned, target)
        oracle.close()
        assertThat(Files.readString(marker)).isEqualTo("target")
        assertThat(Files.exists(owned, NOFOLLOW_LINKS)).isFalse()
        assertThat(Files.isDirectory(parent)).isTrue()
    }

    @Test fun separateOraclesUnderOneParentCannotDeleteEachOthersFiles(@TempDir root: Path) {
        val parent = Files.createDirectory(root.resolve("parent"))
        val first = DuckDbOracle(parent)
        val second = DuckDbOracle(parent)
        try {
            val marker = Files.writeString(second.testDir.resolve("second.txt"), "second")
            first.close()
            assertThat(Files.exists(marker)).isTrue()
            assertThat(Files.readString(marker)).isEqualTo("second")
            assertThat(first.testDir).isNotEqualTo(second.testDir)
        } finally {
            first.close()
            second.close()
        }
        assertThat(Files.isDirectory(parent)).isTrue()
        Files.list(parent).use { assertThat(it.count()).isZero() }
    }

    @Test fun theDefaultTemporaryDirectoryIsStillOwnedAndRemoved() {
        val owned = DuckDbOracle().use { oracle ->
            assertThat(Files.isDirectory(oracle.testDir)).isTrue()
            Files.writeString(oracle.testDir.resolve("owned.txt"), "temporary")
            oracle.testDir
        }
        assertThat(Files.exists(owned, NOFOLLOW_LINKS)).isFalse()
    }
}
