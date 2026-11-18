package com.tom.rv2ide.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.tom.rv2ide.R

class GitHubStudioActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_github_studio)
        findViewById<MaterialToolbar>(R.id.githubToolbar).apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.githubStudioContainer, com.tom.rv2ide.fragments.GitHubStudioFragment())
                .commit()
        }
    }
}
