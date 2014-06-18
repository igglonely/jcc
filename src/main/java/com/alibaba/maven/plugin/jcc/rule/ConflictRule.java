package com.alibaba.maven.plugin.jcc.rule;

import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

import com.alibaba.maven.plugin.jcc.component.checker.ConflictChecker;
import com.alibaba.maven.plugin.jcc.pojo.JccArtifact;

public class ConflictRule implements EnforcerRule {

	private String g;
	private String a;
	private String v;

	private Map<String, JccArtifact> projectArtifactsMap;

	private Map<String, JccArtifact> checkArtifactsMap;

	private MavenProject project;

	private MavenProject checkProject;

	private MavenSession session;

	private DependencyResolutionResult projectResult;

	private DependencyResolutionResult paramResult;

	private ArtifactResolver artifactResolver;
	
	private RepositorySystemSession repositorySystemSession;
	
	
	@Override
	public void execute(EnforcerRuleHelper helper) throws EnforcerRuleException {

		Log log = helper.getLog();
		long startTime = System.currentTimeMillis();
		try {
			if (StringUtils.isBlank(g)) {
				g = (String) helper.evaluate("${g}");
				if (StringUtils.isBlank(g)) {
					log.info("Variable [g] is null");
					return; // �����κδ���
				}
			}

			if (StringUtils.isBlank(a)) {
				a = (String) helper.evaluate("${a}");
				if (StringUtils.isBlank(a)) {
					log.info("Variable [a] is null");
					return; // �����κδ���
				}
			}

			if (StringUtils.isBlank(v)) {
				v = (String) helper.evaluate("${v}");
				if (StringUtils.isBlank(v)) {
					log.info("Variable [v] is null");
					return; // �����κδ���
				}
			}

			// ��ȡ��Ҫ�õ���maven����������ı���
			RepositorySystem repositorySystem = (RepositorySystem) helper
					.getComponent(RepositorySystem.class);
			ProjectBuilder projectBuilder = (ProjectBuilder) helper
					.getComponent(ProjectBuilder.class);
			project = (MavenProject) helper.evaluate("${project}");
			session = (MavenSession) helper.evaluate("${session}");
			// ���������JAR
			artifactResolver = (ArtifactResolver) helper
					.getComponent(ArtifactResolver.class);
			ProjectDependenciesResolver projectDependenciesResolver = (ProjectDependenciesResolver) helper
					.getComponent(ProjectDependenciesResolver.class);
			repositorySystemSession = session.getRepositorySession();			

			// ��ȡ���̵�������
			projectResult = createResolutionResult(projectDependenciesResolver,
					project, session);

			// ��ȡ�������JAR��������
			org.apache.maven.artifact.Artifact artifact = repositorySystem
					.createProjectArtifact(g, a, v);
			ProjectBuildingResult result = projectBuilder.build(artifact,
					project.getProjectBuildingRequest());
			checkProject = result.getProject();
			paramResult = createResolutionResult(projectDependenciesResolver,
					checkProject, session);

			analysis();

			long endTime = System.currentTimeMillis();
			System.out.print("\t**  end check jar conflict,consumer time ["
					+ (endTime - startTime)
					+ "ms]                                                **"
					+ "\n");
			System.out
					.print("\t**************************************************************************************************"
							+ "\n");
			System.out.println();
			System.out.println();
		} catch (ComponentLookupException e) {
			throw new EnforcerRuleException("Unable to lookup a component "
					+ e.getLocalizedMessage(), e);
		} catch (Exception e) {
			throw new EnforcerRuleException("Unable to lookup a component "
					+ e.getLocalizedMessage(), e);
		}
	}

	private void analysis() throws Exception {	
		projectArtifactsMap = filter(projectResult.getDependencyGraph(), null, 0, "");
		checkArtifactsMap = filter(paramResult.getDependencyGraph(), null, 1, "param");

		ConflictChecker conflictChecker = new ConflictChecker(this);
		conflictChecker.start();
	}

	private Map<String, JccArtifact> filter(DependencyNode root,
			JccArtifact parentJccArtifact, int depth, String type)
			throws ComponentLookupException, ArtifactResolutionException {

		Map<String, JccArtifact> jccArtifacts = new HashMap<String, JccArtifact>();

		if (root == null) {
			return jccArtifacts;
		}

		Dependency dependency = root.getDependency();
		JccArtifact jccArtifact = new JccArtifact();
		jccArtifact.setParentJccArtifact(parentJccArtifact);
		jccArtifact.setManagedBits(root.getManagedBits());

		String gid, aid, version;

		if (dependency == null) {

			ArtifactRequest request = null;
			if ("param".equals(type)) {
				request = new ArtifactRequest(paramResult.getDependencyGraph()
						.getArtifact(),
						checkProject.getRemoteProjectRepositories(), "project");
			} else {
				request = new ArtifactRequest(projectResult
						.getDependencyGraph().getArtifact(),
						project.getRemoteProjectRepositories(), "project");
			}
			ArtifactResult artifactResult = artifactResolver.resolveArtifact(repositorySystemSession, request);

			gid = artifactResult.getArtifact().getGroupId();
			aid = artifactResult.getArtifact().getArtifactId();
			version = artifactResult.getArtifact().getVersion();

			jccArtifact.setFile(artifactResult.getArtifact().getFile());
			jccArtifact.setDepth(depth);
			jccArtifact.setGroupId(gid);
			jccArtifact.setArtifactId(aid);
			jccArtifact.setVersion(version);
			jccArtifact.setScope("");

		} else {
			String scope = dependency.getScope();
			if (isExcludeArtifact(scope)) {
				return null;
			}

			gid = dependency.getArtifact().getGroupId();
			aid = dependency.getArtifact().getArtifactId();
			version = dependency.getArtifact().getVersion();

			jccArtifact.setFile(dependency.getArtifact().getFile());
			jccArtifact.setDepth(depth);
			jccArtifact.setGroupId(gid);
			jccArtifact.setArtifactId(aid);
			jccArtifact.setVersion(version);
			jccArtifact.setScope(dependency.getScope());
		}

		if ("param".equals(type) && ignoreConflict(gid, aid, depth)) {
			return jccArtifacts;
		} 
		
		jccArtifacts.put(gid + "." + aid, jccArtifact);

		List<DependencyNode> childs = root.getChildren();
		if (childs == null || childs.size() == 0) {
			return jccArtifacts;
		}

		depth++;

		for (DependencyNode node : childs) {
			Map<String, JccArtifact> sub = filter(node, jccArtifact, depth,
					type);
			if (sub != null && sub.size() > 0) {
				jccArtifacts.putAll(sub);
			}
		}
		return jccArtifacts;

	}

	/**
	 * ��������JAR�ڹ�������ڲ�����ȴ��ڴ����˵�JAR����ȣ���ô���Ժ������JAR�Լ�����������JAR�ĳ�ͻ 
	 * @param gid
	 * @param aid
	 * @param depth
	 * @return
	 */
	private boolean ignoreConflict(String gid, String aid, int depth) {
		JccArtifact jccArtifact = projectArtifactsMap.get(gid + "." + aid);		
		if(jccArtifact != null &&  
			(depth > jccArtifact.getDepth() ||
				(jccArtifact.getManagedBits() != 0  && 
				     (jccArtifact.getManagedBits()& DependencyNode.MANAGED_VERSION) == DependencyNode.MANAGED_VERSION))){
			return true;
		}
		return false;		
	}

	/**
	 * ��������JAR�ڹ�������ڲ������С�ڵ��ڴ����˵�JAR����ȣ���ô���ܺ������JAR�ĳ�ͻ������ֻ��Ҫ������JAR�ĳ�ͻ ����Ҫ�������JAR 
	 * ��Ϊ���ǵ�����ų����ǹ���������ͬ��JAR��������һ���°汾��JAR���°汾�����Ŀ����ֻ����µ�JAR�����룬�������ﲻ�����������ų�
	 * @param gid
	 * @param aid
	 * @param depth
	 * @return
	 
	private boolean ignoreChild(String gid, String aid, int depth) {
		JccArtifact jccArtifact = projectArtifactsMap.get(gid + "." + aid);
		
	}*/

	private boolean isExcludeArtifact(String scope) {

		if (StringUtils.isBlank(scope)) {
			return false;
		}

		Collection<String> exludes = new HashSet<String>();
		Collections.addAll(exludes, "system", "provided", "test");
		if (exludes.contains(scope)) {
			return true;
		}

		return false;

	}

	@SuppressWarnings("unchecked")
	private DependencyResolutionResult createResolutionResult(
			ProjectDependenciesResolver resolver, MavenProject project,
			MavenSession session) throws Exception {
		if (resolver == null || project == null || session == null) {
			return null;
		}
		DefaultDependencyResolutionRequest request = new DefaultDependencyResolutionRequest(
				project, session.getRepositorySession());
		// ���˵�test��provided������
		Collection<String> excludeColloection = new HashSet<String>();
		Collections.addAll(excludeColloection, "test", "provided");

		Class<DependencyFilter> filterClass = (Class<DependencyFilter>) project
				.getClass()
				.getClassLoader()
				.loadClass(
						"org.eclipse.aether.util.filter.ScopeDependencyFilter");
		Constructor<DependencyFilter> filterConstructor = filterClass
				.getDeclaredConstructor(new Class[] { Collection.class,
						Collection.class });
		DependencyFilter collectionFilter = (DependencyFilter) filterConstructor
				.newInstance(null, excludeColloection);

		request.setResolutionFilter(collectionFilter);
		return resolver.resolve(request);
	}

	@Override
	public boolean isCacheable() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isResultValid(EnforcerRule cachedRule) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public String getCacheId() {
		// TODO Auto-generated method stub
		return null;
	}	

	public Map<String, JccArtifact> getProjectArtifactsMap() {
		return projectArtifactsMap;
	}

	public Map<String, JccArtifact> getCheckArtifactsMap() {
		return checkArtifactsMap;
	}

	public MavenProject getProject() {
		return project;
	}

	public MavenProject getCheckProject() {
		return checkProject;
	}

}
