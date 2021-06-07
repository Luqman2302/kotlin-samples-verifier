package com.samples.pusher.core

import com.samples.pusher.core.model.PusherConfiguration
import com.samples.pusher.core.utils.*
import com.samples.verifier.Code
import com.samples.verifier.GitException
import com.samples.verifier.model.CollectionOfRepository
import com.samples.verifier.model.ExecutionResult
import com.samples.verifier.model.ProjectSeverity
import org.eclipse.egit.github.core.Issue
import org.eclipse.egit.github.core.PullRequest
import org.eclipse.egit.github.core.PullRequestMarker
import org.eclipse.egit.github.core.RepositoryId
import org.eclipse.egit.github.core.client.GitHubClient
import org.eclipse.egit.github.core.service.IssueService
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.slf4j.LoggerFactory
import java.io.File


typealias CollectionSamples = Map<Code, ExecutionResult>

data class Snippet(val code: Code, val res: ExecutionResult)

class SamplesPusherImpl(
  val url: String,
  val user: String,
  val password: String = "",
  val baseBranch: String = "master",
  val headBranch: String = "verifier/new-samples",
  val path: String = "",
  templatePath: String = "templates"
) : SamplesPusher {
  private val templates = TemplateManager()

  init {
    templates.configureTemplate(templatePath)
  }

  private val logger = LoggerFactory.getLogger("Samples Pusher")
  private var configuraton: PusherConfiguration = PusherConfiguration()

  override fun readConfigFromFile(filename: String): SamplesPusherImpl {
    configuraton.readFromFile(filename)
    return this
  }

  override fun configure(fn: PusherConfiguration.() -> Unit): SamplesPusherImpl {
    configuraton.apply(fn)
    return this
  }


  private val ghClient: GitHubClient by lazy { createGHClient() }


  override fun push(collection: CollectionOfRepository, isCreateIssue: Boolean): Boolean {
    if (collection.snippets.isEmpty() && collection.diff?.deletedFiles.isNullOrEmpty()) {
      logger.info("Nothing is to push")
      return true
    }

    val existedPrId = getPR(baseBranch, headBranch)?.number // PR already created
    val branch = if (existedPrId != null) headBranch else baseBranch

    return cloneOrInitRepositoryToDir(url, branch) { git ->
      git.checkout()
        .setCreateBranch(existedPrId == null)
        .setName(headBranch)
        .call()

      val dirSamples = prepareTargetPath(git.repository.workTree)

      val mng = SnippetManager(dirSamples)
      val badSnippets =
        writeAndDeleteSnippets(mng, collection.snippets, collection.diff?.deletedFiles ?: emptyList())
      logger.debug("Snippet files are  written")

      if (isCreateIssue && badSnippets.isNotEmpty())
        createIssue(badSnippets, collection, collection.url)

      val df = diffWorking(git)
      if (df.isNotEmpty()) {
        commitAndPush(git)
        logger.debug("Snippets are pushed into branch: $headBranch")

        val changedFiles =
          getModifiedOrAddedFilenames(df).mapTo(HashSet()) { mng.translateFilenameToAddedSnippetPath(it) }
        if (existedPrId == null) {
          createPR(collection, badSnippets, changedFiles.toList(), headBranch) // create bew PR
        } else {
          createNewSamplesCommentPR(existedPrId.toLong(), collection, badSnippets, changedFiles.toList())
        }
      }
      badSnippets.isEmpty()
    } ?: true
  }

  private fun <T> cloneOrInitRepositoryToDir(
    url: String,
    branch: String,
    cb: (Git) -> T
  ): T? {
    val dir = File(url.substringAfterLast('/').substringBeforeLast('.'))
    try {
      val git = if (dir.exists()) {
        logger.debug("Using exist repository... ")
        initRepository(dir)
      } else {
        logger.debug("Cloning the repository... ")
        cloneRepository(dir, url, branch)
      }
      return git.use(cb)
    } catch (e: GitException) {
      logger.error("${e.message}")
    } finally {
      if (dir.isDirectory) {
        dir.deleteRecursively()
      } else {
        dir.delete()
      }
    }
    return null
  }

  override fun filterBadSnippets(res: CollectionSamples): List<Snippet> {
    return res.filter {
      it.value.errors.any { err ->
        greaterOrEqualSeverity(err.severity)
      }
    }.map { Snippet(it.key, it.value) }
  }

  fun getPR(baseBranch: String, headBranch: String): PullRequest? {
    val prService = org.eclipse.egit.github.core.service.PullRequestService(ghClient)
    val repoId = RepositoryId.createFromUrl(url)
    val prs = prService.getPullRequests(repoId, "open") // GitHub API supports the filters
    return prs.find {
      it.base.ref == baseBranch
        && it.head.ref == headBranch
        && it.head.repo.id == it.base.repo.id /*the same repo*/
    }
  }

  private fun prepareTargetPath(repoDir: File): File {
    val dirSamples = repoDir.resolve(path)
    if (!dirSamples.exists())
      if (!dirSamples.mkdirs()) {
        throw Exception("Can't create directory by path ${dirSamples.path}")
      }

    logger.debug("Created the path ${dirSamples.path}")
    return dirSamples
  }

  private fun writeAndDeleteSnippets(
    manager: SnippetManager,
    res: CollectionSamples,
    deleteFiles: List<String>
  ): List<Snippet> {
    deleteFiles.forEach { manager.removeAllSnippets(it) }
    val badSnippets = mutableListOf<Snippet>()
    res.forEach {
      if (it.value.errors.isNotEmpty()) {
        logger.error("Filename: ${it.value.fileName}")
        logger.error("Code: \n${it.key}")
        logger.error("Errors: \n${it.value.errors.joinToString("\n")}")
      }

      if (it.value.errors.any { err ->
          greaterOrEqualSeverity(err.severity)
        }) {
        badSnippets.add(Snippet(it.key, it.value))
      } else {
        manager.addSnippet(it.key, it.value.fileName)
      }
    }
    return badSnippets
  }

  private fun greaterOrEqualSeverity(severity: ProjectSeverity): Boolean {
    return severity >= configuraton.severity
  }

  private fun commitAndPush(git: Git) {
    git.add().addFilepattern(".").call()
    git.commit()
      .setAll(true)
      .setAllowEmpty(true)
      .setMessage(configuraton.commitMessage)
      .setCommitter(configuraton.committerName, configuraton.committerEmail)
      .call()

    val credentialsProvider: CredentialsProvider = UsernamePasswordCredentialsProvider(user, password)
    pushRepo(git, url, credentialsProvider)
  }

  // GitHub helpers
  private fun createGHClient(): GitHubClient {
    val client = GitHubClient()

    if (password.isEmpty())
      client.setOAuth2Token(user)
    else
      client.setCredentials(user, password)
    return client
  }

  private fun createPR(
    res: CollectionOfRepository,
    badSnippets: List<Snippet>,
    changedFiles: List<String>,
    headBranch: String
  ) {
    val model = HashMap<String, Any>()
    model["badSnippets"] = badSnippets
    model["src"] = res
    model["changedFiles"] = changedFiles
    val temp = templates.getTemplate(TemplateType.PR, model)

    val prService = org.eclipse.egit.github.core.service.PullRequestService(ghClient)
    var pr = PullRequest()
    pr.title = temp.head
    pr.body = temp.body
    pr.base = PullRequestMarker().setLabel(configuraton.baseBranchPR)
    pr.head = PullRequestMarker().setLabel(headBranch)
    //logger.debug("RepoId: " + RepositoryId.createFromUrl(url).generateId())
    pr = prService.createPullRequest(RepositoryId.createFromUrl(url), pr)
    logger.info("The Push request is created, url: ${pr.htmlUrl}")
  }


  private fun createIssue(
    badSnippets: List<Snippet>,
    res: CollectionOfRepository,
    repositoryUrl: String = url
  ) {
    val model = HashMap<String, Any>()
    model["snippets"] = badSnippets
    model["src"] = res
    val temp = templates.getTemplate(TemplateType.ISSUE, model)

    val issueService = IssueService(ghClient)
    var issue = Issue()
    issue.title = temp.head
    issue.body = temp.body

    issue = issueService.createIssue(RepositoryId.createFromUrl(repositoryUrl), issue)
    logger.info("The Issue  is created, url: ${issue.htmlUrl}")
  }

  fun createNewSamplesCommentPR(
    id: Long,
    res: CollectionOfRepository,
    badSnippets: List<Snippet>,
    changedFiles: List<String>,
    repositoryUrl: String = url
  ) {
    val model = HashMap<String, Any>()
    model["badSnippets"] = badSnippets
    model["src"] = res
    model["changedFiles"] = changedFiles
    val temp = templates.getTemplate(TemplateType.PR, model)

    createCommentPR(repositoryUrl, id, temp)
  }

  override fun createBadSamplesCommentPR(
    id: Long,
    badSnippets: List<Snippet>,
    res: CollectionOfRepository,
    repositoryUrl: String
  ) {
    val model = HashMap<String, Any>()
    model["snippets"] = badSnippets
    model["src"] = res
    val temp = templates.getTemplate(TemplateType.PR_COMMENT, model)

    createCommentPR(repositoryUrl, id, temp)
  }

  private fun createCommentPR(
    repositoryUrl: String,
    id: Long,
    temp: TemplateManager.Template
  ) {
    val issueService = IssueService(ghClient)
    val comment = issueService.createComment(RepositoryId.createFromUrl(repositoryUrl), id.toInt(), temp.body)
    logger.info("The pr comment  is created, url: ${comment.url}")
  }

}