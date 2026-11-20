package com.nox.feature.agent.domain.skill

import com.nox.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局技能来源：`~/.nox/skills`，跨项目共享。以容器路径经 [FileAccessProvider] 访问，
 * 跟随当前执行环境——本地模式落 App 私有 `filesDir/nox/skills`（容器内 `/root/.nox/skills`），
 * 远程模式经 SSH 落服务器用户 home 下的 `.nox/skills`。远程是独立执行环境，读写均以远端为准。
 */
@Singleton
class GlobalDirectorySkillSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : SkillSource {

    val skillsRoot: String = "~/.nox/skills"

    override fun listSkills(): List<Skill> = SkillDirectoryScanner.scan(fileAccess, skillsRoot)

    override fun loadInstructions(name: String): String? =
        listSkills().firstOrNull { it.name.equals(name, ignoreCase = true) }?.instructions
}
