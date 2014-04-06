package org.ajoberstar.gradle.git.semver

import com.github.zafarkhaja.semver.GrammarException
import com.github.zafarkhaja.semver.Version
import com.github.zafarkhaja.semver.util.UnexpectedElementTypeException

import org.ajoberstar.grgit.Commit
import org.ajoberstar.grgit.Grgit

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Locates the nearest {@link org.ajoberstar.grgit.Tag tag}s whose names can be
 * parsed as a {@link com.github.zafarkhaja.semver.Version version}. Both the
 * absolute nearest version tag and the nearest "normal version" tag are
 * included.
 *
 * <p>
 *   Primarily used as part of version inference to determine the previous
 *   version.
 * </p>
 *
 * @since 0.8.0
 */
final class NearestVersionLocator {
	private static final Logger logger = LoggerFactory.getLogger(NearestVersionLocator)

	private NearestVersionLocator() {
		throw new AssertionError('Cannot instantiate this class.')
	}

	/**
	 * Locate the nearest version in the given repository
	 * starting from the current HEAD.
	 *
	 * <p>
	 * All tag names are parsed to determine if they are valid
	 * version strings. Tag names can begin with "v" (which will
	 * be stripped off).
	 * </p>
	 *
	 * <p>
	 * The nearest tag is determined by getting a commit log between
	 * the tag and {@code HEAD}. The version tag with the smallest
	 * log from a pure count of commits will have its version returned. If two
	 * version tags have a log of the same size, it is undefined which will be
	 * returned.
	 * </p>
	 *
	 * <p>
	 * Two versions will be returned: the "any" version and the "normal" version.
	 * "Any" is the absolute nearest tagged version. "Normal" is the nearest
	 * tagged version that does not include a pre-release segment.
	 * </p>
	 *
	 * @param grgit the repository to locate the tag in
	 * @param fromRevStr the revision to consider current.
	 * Defaults to {@code HEAD}.
	 * @return the version corresponding to the nearest tag
	 */
	static NearestVersion locate(Grgit grgit) {
		logger.debug('Locate beginning on branch: {}', grgit.branch.current.fullName)
		Commit head = grgit.head()
		List versionTags = grgit.tag.list().inject([]) { list, tag ->
			Version version = parseAsVersion(tag.name)
			logger.debug('Tag {} parsed as {} version.', tag.fullName, version)
			if (version) {
				def data
				if (tag.commit == head) {
					logger.debug('Tag {} is at head. Including as candidate.', tag.fullName)
					data = [version: version, distance: 0]
				} else {
					def unreachableCommitLog = grgit.log {
						range head.id, tag.commit.id
					}
					logger.debug('Unreachable commits in tag {}: {}', tag.fullName, unreachableCommitLog.collect { it.abbreviatedId })
					def unreachableCommits = unreachableCommitLog.size()
					if (unreachableCommits) {
						logger.debug('Tag {} has {} unreachable commits. Excluding as candidate.', tag.fullName, unreachableCommits)
					} else {
						logger.debug('Tag {} has {} unreachable commits. Including as candidate.', tag.fullName, unreachableCommits)
						def reachableCommitLog = grgit.log {
							range tag.commit.id, head.id
						}
						logger.debug('Reachable commits after tag {}: {}', tag.fullName, reachableCommitLog.collect { it.abbreviatedId })
						def distance = reachableCommitLog.size()
						data = [version: version, distance: distance]
					}
				}
				if (data) {
					logger.debug('Tag data found: {}', data)
					list << data
				}
			}
			list
		}

		Map normal = versionTags.findAll { versionTag ->
			versionTag.version.preReleaseVersion.empty
		}.min { versionTag ->
			versionTag.distance
		}

		Map any = versionTags.min { versionTag ->
			versionTag.distance
		}

		Version anyVersion = any ? any.version : Version.valueOf('0.0.0')
		Version normalVersion = normal ? normal.version : Version.valueOf('0.0.0')
		int distance = normal ? normal.distance : grgit.log(includes: [head.id]).size()

		return new NearestVersion(anyVersion, normalVersion, distance)
	}

	protected static Version parseAsVersion(String name) {
		try {
			return Version.valueOf(extractName(name))
		} catch (GrammarException e) {
			logger.error('Internal semver error.')
			throw e
		} catch (UnexpectedElementTypeException e) {
			logger.debug('Invalid version string: {}', name)
			return null
		}
	}

	protected static String extractName(String tagName) {
		if (tagName.charAt(0) == 'v') {
			return tagName[1..-1]
		} else {
			return tagName
		}
	}
}
