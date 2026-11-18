package com.tom.rv2ide.github

import org.junit.Assert.*
import org.junit.Test

class GitHubValidationTest {
    @Test fun validatesRepositoryPathSegments() {
        assertTrue(GitHubValidation.isValidOwnerOrRepo("faria2177"))
        assertTrue(GitHubValidation.isValidOwnerOrRepo("SELFACS-android"))
        assertFalse(GitHubValidation.isValidOwnerOrRepo("../SELFACS"))
        assertFalse(GitHubValidation.isValidOwnerOrRepo("owner/repo"))
        assertFalse(GitHubValidation.isValidOwnerOrRepo("bad name"))
    }

    @Test fun validatesTokenAndBranchInput() {
        assertTrue(GitHubValidation.isValidToken("github_pat_12345678901234567890"))
        assertFalse(GitHubValidation.isValidToken("short"))
        assertFalse(GitHubValidation.isValidToken("token with spaces 123456789"))
        assertTrue(GitHubValidation.isValidBranch("feature/phase-10"))
        assertFalse(GitHubValidation.isValidBranch("../main"))
        assertFalse(GitHubValidation.isValidBranch("bad branch"))
    }
}
