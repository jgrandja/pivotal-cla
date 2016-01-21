/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.pivotal.cla.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.egit.github.core.Comment;
import org.eclipse.egit.github.core.CommitStatus;
import org.eclipse.egit.github.core.Repository;
import org.eclipse.egit.github.core.RepositoryHook;
import org.eclipse.egit.github.core.RepositoryId;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.service.IssueService;
import org.eclipse.egit.github.core.service.RepositoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import io.pivotal.cla.ClaOAuthConfig;
import io.pivotal.cla.data.AccessToken;
import io.pivotal.cla.data.User;
import io.pivotal.cla.data.repository.AccessTokenRepository;
import io.pivotal.cla.egit.github.core.ContextCommitStatus;
import io.pivotal.cla.egit.github.core.Email;
import io.pivotal.cla.egit.github.core.EventsRepositoryHook;
import io.pivotal.cla.egit.github.core.service.ContextCommitService;
import io.pivotal.cla.egit.github.core.service.EmailService;

@Component
public class MylynGithubService implements GitHubService {

	@Autowired
	AccessTokenRepository tokenRepo;

	@Autowired
	ClaOAuthConfig oauthConfig;

	@Autowired
	AccessTokenService tokenService;

	RestTemplate rest = new RestTemplate();

	@Override
	public List<String> findRepositoryNames(String accessToken) throws IOException {
		GitHubClient client = new GitHubClient();
		client.setOAuth2Token(accessToken);

		RepositoryService service = new RepositoryService(client);
		List<Repository> repositories = service.getRepositories();
		List<String> repoSlugs = new ArrayList<>();
		for (Repository r : repositories) {
			org.eclipse.egit.github.core.User owner = r.getOwner();
			repoSlugs.add(owner.getLogin() + "/" + r.getName());
		}
		return repoSlugs;
	}

	public void save(io.pivotal.cla.service.CommitStatus commitStatus) {
		String repoId = commitStatus.getRepoId();
		AccessToken token = tokenRepo.findOne(repoId);
		if (token == null) {
			return;
		}
		boolean success = commitStatus.isSuccess();
		RepositoryId id = RepositoryId.createFromId(repoId);
		GitHubClient client = createClient(token.getToken());
		ContextCommitService commitService = new ContextCommitService(client);
		ContextCommitStatus status = new ContextCommitStatus();
		status.setDescription(success ? "Thank you for signing the Contributor License Agreement!"
				: "Please sign the Contributor Licenese Agreement!");
		status.setState(success ? CommitStatus.STATE_SUCCESS : CommitStatus.STATE_FAILURE);
		status.setContext("ci/pivotal-cla");
		status.setUrl(commitStatus.getUrl());
		status.setTargetUrl(status.getUrl());

		try {
			commitService.createStatus(id, commitStatus.getSha(), status);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}


		GitHubClient commentClient = new GitHubClient();
		commentClient.setOAuth2Token(oauthConfig.getPivotalClaAccessToken());
		IssueService issues = new IssueService(commentClient);
		long claUserCommentCount = getCommentsByClaUser(issues, id, commitStatus);
		if(success) {
			try {
				issues.createComment(id, commitStatus.getPullRequestId(), "@" + commitStatus.getGithubUsername() + " Thank you for signing the [Contributor License Agreement](" + status.getUrl() + ")!");
			} catch(IOException e) {
				throw new RuntimeException(e);
			}
		} else if(claUserCommentCount < 1) {
			try {
				issues.createComment(id, commitStatus.getPullRequestId(), "@" + commitStatus.getGithubUsername() + " Please sign the [Contributor License Agreement](" + status.getUrl() + ")!");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private long getCommentsByClaUser(IssueService issues, RepositoryId id, io.pivotal.cla.service.CommitStatus commitStatus) {
		try {
			return issues.getComments(id, commitStatus.getPullRequestId()).stream().filter( c -> "pivotal-cla".equals(c.getUser().getLogin())).count();
		}catch(IOException e) {
			throw new RuntimeException(e);
		}
	}

	private GitHubClient createClient(String accessToken) {
		GitHubClient client = new GitHubClient();
		client.setOAuth2Token(accessToken);
		return client;
	}

	public User getCurrentUser(CurrentUserRequest request) {
		AccessTokenRequest tokenRequest = new AccessTokenRequest();
		tokenRequest.setCredentials(request.isRequestAdminAccess() ? oauthConfig.getAdmin() : oauthConfig.getMain());
		tokenRequest.setOauthParams(request.getOauthParams());
		String token = tokenService.getToken(tokenRequest);
		try {
			GitHubClient client = createClient(token);

			org.eclipse.egit.github.core.service.UserService githubUsers = new org.eclipse.egit.github.core.service.UserService(
					client);
			EmailService emailService = EmailService.forOAuth(token);
			List<String> verifiedEmails = emailService.getEmails().stream().filter(e -> e.isVerified())
					.map(Email::getEmail).collect(Collectors.toList());
			org.eclipse.egit.github.core.User githubUser = githubUsers.getUser();

			User user = new User();
			user.setName(githubUser.getName());
			user.setAccessToken(token);
			user.setAvatarUrl(githubUser.getAvatarUrl());
			user.setEmails(new HashSet<>(verifiedEmails));
			user.setGithubLogin(githubUser.getLogin());
			user.setAdmin(request.isRequestAdminAccess() && hasAdminEmail(user));
			return user;

		} catch (IOException fail) {
			throw new RuntimeException(fail);
		}
	}

	private boolean hasAdminEmail(User user) {
		return user.getEmails().stream().anyMatch(e -> e.endsWith("@pivotal.io"));
	}

	@Override
	public List<String> createPullRequestHooks(CreatePullRequestHookRequest request)
			throws IOException {
		String accessToken = request.getAccessToken();
		List<String> repositoryIds = request.getRepositoryIds();
		String githubEventUrl = request.getGithubEventUrl();

		GitHubClient client = createClient(accessToken);
		RepositoryService service = new RepositoryService(client);
		List<String> hookUrls = new ArrayList<>();

		for (String repository : repositoryIds) {
			AccessToken token = new AccessToken();
			token.setId(repository);
			token.setToken(accessToken);

			tokenRepo.save(token);

			EventsRepositoryHook hook = createHook(githubEventUrl);
			RepositoryHook createdHook = service.createHook(RepositoryId.createFromId(repository), hook);
			long hookId = createdHook.getId();
			hookUrls.add("https://github.com/" + repository + "/settings/hooks/" + hookId);
		}
		return hookUrls;
	}

	public ContributingUrlsResponse getContributingUrls(List<String> repositoryIds) {
		Set<String> remainingRepositoryIds = new HashSet<>(repositoryIds);

		Map<String,String> mdUrls = createEditLinks(remainingRepositoryIds, "CONTRIBUTING.md");
		remainingRepositoryIds.removeAll(mdUrls.keySet());
		Map<String,String> adocUrls = createEditLinks(remainingRepositoryIds, "CONTRIBUTING.adoc");
		remainingRepositoryIds.removeAll(adocUrls.keySet());
		List<String> newUrls = createNewLinks(remainingRepositoryIds, "CONTRIBUTING.adoc");

		ContributingUrlsResponse response = new ContributingUrlsResponse();
		response.setMarkdown(mdUrls.values());
		response.setAsciidoc(adocUrls.values());
		response.getAsciidoc().addAll(newUrls);

		return response;
	}

	private Map<String,String> createEditLinks(Collection<String> repoIds, String fileName) {
		Map<String,String> urls = new HashMap<>();
		for(String id : repoIds) {
			String url = "https://github.com/"+ id +"/edit/master/" + fileName;
			if(urlExists(url)) {
				urls.put(id, url);
			}
		}
		return urls;
	}

	private List<String> createNewLinks(Collection<String> repoIds, String fileName) {
		List<String> urls = new ArrayList<>();
		for(String id : repoIds) {
			String url = "https://github.com/"+ id +"/new/master?filename=" + fileName;
			urls.add(url);
		}
		return urls;
	}

	private boolean urlExists(String url) {
		try {
			rest.getForEntity(url, String.class);
			return true;
		}catch(HttpClientErrorException notFound) {
			return false;
		}
	}

	private EventsRepositoryHook createHook(String url) {
		Map<String, String> config = new HashMap<>();
		config.put("url", url);
		config.put("content_type", "json");
		EventsRepositoryHook hook = new EventsRepositoryHook();
		hook.setActive(true);
		hook.addEvent("pull_request");
		hook.setName("web");
		hook.setConfig(config);
		return hook;
	}
}
