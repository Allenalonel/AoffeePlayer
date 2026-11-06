package com.webdav.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDirectoryTest {
    private val sampleServer =
        WebDavServer(
            id = 1L,
            name = "My NAS",
            url = "http://nas.local",
            port = 5005,
            isDefault = true,
        )

    @Test
    fun normalizePath_variousFormats_normalizesCorrectly() {
        assertEquals("/", RemoteDirectory.normalizePath("/"))
        assertEquals("/", RemoteDirectory.normalizePath(""))
        assertEquals("/", RemoteDirectory.normalizePath("//"))
        assertEquals("/", RemoteDirectory.normalizePath("///"))
        assertEquals("/", RemoteDirectory.normalizePath("\\"))
        assertEquals("/Music/", RemoteDirectory.normalizePath("Music"))
        assertEquals("/Music/", RemoteDirectory.normalizePath("/Music"))
        assertEquals("/Music/", RemoteDirectory.normalizePath("/Music/"))
        assertEquals("/Music/Rock/Queen/", RemoteDirectory.normalizePath("//Music///Rock//Queen"))
        assertEquals("/Music/Rock/", RemoteDirectory.normalizePath("\\Music\\Rock\\"))
        assertEquals("/Music/Rock/", RemoteDirectory.normalizePath("\\Music/Rock\\"))
    }

    @Test
    fun getParentPath_rootAndEmpty_returnsNull() {
        assertNull(RemoteDirectory.getParentPath("/"))
        assertNull(RemoteDirectory.getParentPath(""))
        assertNull(RemoteDirectory.getParentPath("//"))
    }

    @Test
    fun getParentPath_firstLevel_returnsRoot() {
        assertEquals("/", RemoteDirectory.getParentPath("/Music"))
        assertEquals("/", RemoteDirectory.getParentPath("/Music/"))
    }

    @Test
    fun getParentPath_multiLevel_returnsImmediateParentWithTrailingSlash() {
        assertEquals("/Music/", RemoteDirectory.getParentPath("/Music/Rock"))
        assertEquals("/Music/", RemoteDirectory.getParentPath("/Music/Rock/"))
        assertEquals("/Music/Rock/", RemoteDirectory.getParentPath("/Music/Rock/Queen/"))
    }

    @Test
    fun isAncestor_evaluatesHierarchyCorrectly() {
        assertTrue(RemoteDirectory.isAncestor("/", "/Music/"))
        assertTrue(RemoteDirectory.isAncestor("/", "/Music/Rock/"))
        assertTrue(RemoteDirectory.isAncestor("/Music/", "/Music/Rock/Queen/"))

        // Same path is not an ancestor of itself
        assertFalse(RemoteDirectory.isAncestor("/", "/"))
        assertFalse(RemoteDirectory.isAncestor("/Music/Rock/", "/Music/Rock/"))

        // Sibling or cousin paths
        assertFalse(RemoteDirectory.isAncestor("/Music/Rock/", "/Music/Jazz/"))

        // Prefix match prevention: /Music/Rock is not ancestor of /Music/RockStar/
        assertFalse(RemoteDirectory.isAncestor("/Music/Rock", "/Music/RockStar/"))
    }

    @Test
    fun buildBreadcrumbs_forRootWithServer_returnsServerName() {
        val crumbs = RemoteDirectory.buildBreadcrumbs(sampleServer, "/")
        assertEquals(1, crumbs.size)
        assertEquals("My NAS", crumbs[0].name)
        assertEquals("/", crumbs[0].path)
    }

    @Test
    fun buildBreadcrumbs_withoutServer_defaultsToRootLabel() {
        val crumbs = RemoteDirectory.buildBreadcrumbs(null, "/")
        assertEquals(1, crumbs.size)
        assertEquals("根目录", crumbs[0].name)
        assertEquals("/", crumbs[0].path)
    }

    @Test
    fun buildBreadcrumbs_deepPath_createsCompleteHierarchy() {
        val crumbs = RemoteDirectory.buildBreadcrumbs(sampleServer, "/Music/Rock/Queen/")
        assertEquals(4, crumbs.size)
        assertEquals(Breadcrumb("My NAS", "/"), crumbs[0])
        assertEquals(Breadcrumb("Music", "/Music/"), crumbs[1])
        assertEquals(Breadcrumb("Rock", "/Music/Rock/"), crumbs[2])
        assertEquals(Breadcrumb("Queen", "/Music/Rock/Queen/"), crumbs[3])
    }

    @Test
    fun buildBreadcrumbs_messySlashes_collapsesCorrectly() {
        val crumbs = RemoteDirectory.buildBreadcrumbs(sampleServer, "//Music///Rock//")
        assertEquals(3, crumbs.size)
        assertEquals(Breadcrumb("My NAS", "/"), crumbs[0])
        assertEquals(Breadcrumb("Music", "/Music/"), crumbs[1])
        assertEquals(Breadcrumb("Rock", "/Music/Rock/"), crumbs[2])
    }

    @Test
    fun buildBreadcrumbs_localStorageRoot_returnsLocalStorageLabel() {
        val localServer = WebDavServer.createLocalStorage()
        val crumbs = RemoteDirectory.buildBreadcrumbs(localServer, "/")
        assertEquals(1, crumbs.size)
        assertEquals("本地存储", crumbs[0].name)
        assertEquals("/", crumbs[0].path)
    }

    @Test
    fun buildBreadcrumbs_localStorageMountedPath_derivesMountedFolderName() {
        val localServer = WebDavServer.createLocalStorage()
        val encodedUri = java.net.URLEncoder.encode("content://com.android.externalstorage.documents/tree/primary%3AMusic", "UTF-8")
        val crumbs = RemoteDirectory.buildBreadcrumbs(localServer, "/local/$encodedUri/")

        assertEquals(2, crumbs.size)
        assertEquals("本地存储", crumbs[0].name)
        assertEquals("Music", crumbs[1].name)
        assertEquals("/local/$encodedUri/", crumbs[1].path)
    }

    @Test
    fun buildBreadcrumbs_localStorageSubfolder_decodesSubfolderName() {
        val localServer = WebDavServer.createLocalStorage()
        val encodedUri = java.net.URLEncoder.encode("content://com.android.externalstorage.documents/tree/primary%3AMusic", "UTF-8")
        val crumbs = RemoteDirectory.buildBreadcrumbs(localServer, "/local/$encodedUri/Rock%20Band/Queen/")

        assertEquals(4, crumbs.size)
        assertEquals("本地存储", crumbs[0].name)
        assertEquals("Music", crumbs[1].name)
        assertEquals("Rock Band", crumbs[2].name)
        assertEquals("/local/$encodedUri/Rock%20Band/", crumbs[2].path)
        assertEquals("Queen", crumbs[3].name)
        assertEquals("/local/$encodedUri/Rock%20Band/Queen/", crumbs[3].path)
    }
}
