package lib

import spock.lang.Specification

class UtilHelpersSpec extends Specification {
  static Object util

  def setupSpec() {
    util = PipelineScriptLoader.load('pipelines/lib/util.groovy')
  }

  def "buildkitCacheArgs emits cache-from and cache-to for the arch"() {
    when:
    def args = util.buildkitCacheArgs('us-central1-docker.pkg.dev/p/buildcache/newinstall', 'amd64')

    then:
    args.contains('--cache-from type=registry,ref=us-central1-docker.pkg.dev/p/buildcache/newinstall:cache-amd64')
    args.contains('--cache-to type=registry,ref=us-central1-docker.pkg.dev/p/buildcache/newinstall:cache-amd64,mode=max')
  }

  def "buildkitCacheArgs omits cache-to when push=false"() {
    when:
    def args = util.buildkitCacheArgs('repo/x', 'amd64', false)

    then:
    args.contains('--cache-from type=registry,ref=repo/x:cache-amd64')
    !args.contains('--cache-to')
  }

  def "buildkitCacheArgs includes cache-to when push=true"() {
    expect:
    util.buildkitCacheArgs('repo/x', 'amd64', true).contains('--cache-to')
  }

  def "buildkitCacheArgs defaults to including cache-to"() {
    expect:
    util.buildkitCacheArgs('repo/x', 'amd64').contains('--cache-to')
  }

  def "cacheIsSharable is false only for a python pinned build"() {
    expect:
    util.cacheIsSharable([:])
    util.cacheIsSharable([LSST_PYTHON_PIN: null])
    util.cacheIsSharable([LSST_PYTHON_PIN: ''])
    !util.cacheIsSharable([LSST_PYTHON_PIN: '3.14'])
  }

  def "sanitizeEupsTag prefixes numeric tags with v and replaces separators"() {
    expect:
    util.sanitizeEupsTag('1.2.3-rc1') == 'v1_2_3_rc1'
    util.sanitizeEupsTag('d_latest') == 'd_latest'
  }

  def "sanitizeDockerTag converts slashes to underscores"() {
    expect:
    util.sanitizeDockerTag('tickets/DM-1') == 'tickets_DM-1'
  }

  def "joinPath joins parts with slashes"() {
    expect:
    util.joinPath('a', 'b', 'c') == 'a/b/c'
    util.joinPath('a') == 'a'
  }

  def "dedent strips common leading indentation after the first newline"() {
    expect:
    util.dedent("\n    foo\n    bar\n") == "foo\nbar\n"
  }

  def "shebangerize prepends a shebang only when absent"() {
    expect:
    util.shebangerize('echo hi') == '#!/bin/sh -xe\necho hi'
    util.shebangerize('#!/bin/bash\necho hi') == '#!/bin/bash\necho hi'
  }

  def "renderPodYaml includes runner container and shared volumes, no sidecar by default"() {
    when:
    def yaml = util.renderPodYaml(image: 'img:1', pullPolicy: 'IfNotPresent', cacheImage: null, mounts: [])

    then:
    yaml.contains('name: runner')
    yaml.contains('image: img:1')
    yaml.contains('imagePullPolicy: IfNotPresent')
    yaml.contains('name: j-workspace')
    yaml.contains('name: home-jenkins')
    yaml.contains('runAsUser: 1000')
    !yaml.contains('name: gcloud-cli')
  }

  def "renderPodYaml adds the gcloud-cli sidecar when cacheImage is set"() {
    when:
    def yaml = util.renderPodYaml(image: 'img:1', pullPolicy: 'Always', cacheImage: 'gc:latest', mounts: [])

    then:
    yaml.contains('name: gcloud-cli')
    yaml.contains('image: gc:latest')
  }

  // The sidecar does the cache tar/zstd work, so its CPU *limit* must leave
  // headroom to burst; a limit equal to the request (the old 500m/500m) put a
  // hard CFS quota ceiling on every multi-GB cache transfer. The request stays
  // small so the otherwise-idle sidecar doesn't block pod packing.
  def "renderPodYaml lets the gcloud-cli sidecar burst above its CPU request"() {
    when:
    def yaml = util.renderPodYaml(image: 'img:1', pullPolicy: 'Always', cacheImage: 'gc:latest')
    def sidecar = yaml.split(/ {2}- name: gcloud-cli/)[1]

    then:
    sidecar.contains('cpu: 500m')
    sidecar.contains('cpu: 8')
    sidecar.contains('memory: 1Gi')
    sidecar.contains('memory: 2Gi')
  }

  def "renderPodYaml allows per-job gcloud-cli sidecar resource overrides"() {
    when:
    def yaml = util.renderPodYaml(image: 'img:1', pullPolicy: 'Always', cacheImage: 'gc:latest',
      cacheCpuRequest: '250m', cacheCpuLimit: '1', cacheMemRequest: '512Mi', cacheMemLimit: '1Gi')
    def sidecar = yaml.split(/ {2}- name: gcloud-cli/)[1]

    then:
    sidecar.contains('cpu: 250m')
    sidecar.contains('cpu: 1')
    sidecar.contains('memory: 512Mi')
    sidecar.contains('memory: 1Gi')
    !sidecar.contains('cpu: 8')
  }

  def "config accessors build strings from sqreConfig"() {
    given:
    util.metaClass.sqreConfig = { ->
      [
        gcloudcli: [docker_registry: [repo: 'ghcr.io/lsst-dm/docker-gcloudcli', tag: 'latest']],
        eups: [service_account: 'eups-dev@prompt-proto.iam.gserviceaccount.com'],
        buildcache: [repo_base: 'us-central1-docker.pkg.dev/prompt-proto/buildcache'],
      ]
    }

    expect:
    util.defaultGcloudCliImage() == 'ghcr.io/lsst-dm/docker-gcloudcli:latest'
    util.eupsServiceAccount() == 'eups-dev@prompt-proto.iam.gserviceaccount.com'
    util.buildcacheRepo('newinstall') == 'us-central1-docker.pkg.dev/prompt-proto/buildcache/newinstall'

    cleanup:
    util.metaClass = null
  }

  def "renderPodYaml pins arm nodes when arch is arm64"() {
    when:
    def yaml = util.renderPodYaml(image: 'img:1', pullPolicy: 'IfNotPresent', cacheImage: null,
      arch: 'arm64', mounts: [])

    then:
    yaml.contains('kubernetes.io/arch: arm64')
    yaml.contains('key: kubernetes.io/arch')
    yaml.contains('effect: NoSchedule')
    yaml.contains('value: arm64')
  }

  def "renderPodYaml omits arm scheduling for x86 and when arch is unset"() {
    expect:
    !util.renderPodYaml(image: 'img:1', pullPolicy: 'IfNotPresent', cacheImage: null,
      arch: 'amd64').contains('kubernetes.io/arch: arm64')
    !util.renderPodYaml(image: 'img:1', pullPolicy: 'IfNotPresent', cacheImage: null
      ).contains('kubernetes.io/arch: arm64')
  }

  def "renderPodYaml selects the x86 compute class and tolerates its taint"() {
    when:
    def yaml = util.renderPodYaml(image: 'img:1', pullPolicy: 'IfNotPresent', cacheImage: null,
      arch: 'amd64', mounts: [])

    then:
    yaml.contains('cloud.google.com/compute-class: jenkins-workers-x86')
    yaml.contains('key: cloud.google.com/compute-class')
    yaml.contains('value: jenkins-workers-x86')
  }

  def "renderPodYaml selects the x86 compute class when arch is unset"() {
    expect:
    util.renderPodYaml(image: 'img:1', pullPolicy: 'IfNotPresent', cacheImage: null)
      .contains('cloud.google.com/compute-class: jenkins-workers-x86')
  }

  def "renderPodYaml selects the arm compute class alongside the arch selector"() {
    when:
    def yaml = util.renderPodYaml(image: 'img:1', pullPolicy: 'IfNotPresent', cacheImage: null,
      arch: 'arm64', mounts: [])

    then:
    yaml.contains('cloud.google.com/compute-class: jenkins-workers-arm')
    yaml.contains('kubernetes.io/arch: arm64')
    yaml.contains('value: jenkins-workers-arm')
    yaml.contains('value: arm64')
  }

  // Every machine family reachable through either ComputeClass accepts
  // hyperdisk-balanced, so arm and x86 share one workspace backing. The volume
  // spec is fixed before scheduling, so this must stay true of any family added
  // as a fallback.
  def "renderPodYaml uses the hyperdisk workspace for both arm and x86"() {
    expect:
    ['arm64', 'amd64'].every { arch ->
      def yaml = util.renderPodYaml(image: 'img:1', pullPolicy: 'IfNotPresent', cacheImage: null,
        arch: arch, mounts: [])
      yaml.contains('hyperdisk-rwo') && yaml.contains('volumeClaimTemplate')
    }
  }

  def "renderPodYaml backs the workspace with an emptyDir only when asked"() {
    when:
    def yaml = util.renderPodYaml(image: 'img:1', pullPolicy: 'IfNotPresent', cacheImage: null,
      arch: 'arm64', emptyDirWorkspace: true, storage: '2Gi', mounts: [])

    then:
    !yaml.contains('hyperdisk-rwo')
    !yaml.contains('volumeClaimTemplate')
    yaml.contains('sizeLimit: 2Gi')
  }

  def "parseImageLabels reads the flat label map from imagetools output"() {
    given:
    def json = new File('src/test/resources/imagetools-labels-sample.json').text

    when:
    def labels = util.parseImageLabels(json)

    then:
    labels.VERSIONDB_MANIFEST_ID == 'b1234'
    labels.LSST_COMPILER == 'conda-system'
  }

  def "parseImageLabels reads skopeo top-level .Labels"() {
    expect:
    util.parseImageLabels('{"Labels":{"LSST_COMPILER":"conda-system"}}').LSST_COMPILER == 'conda-system'
  }

  def "parseImageLabels reads crane/docker .config.Labels"() {
    expect:
    util.parseImageLabels('{"config":{"Labels":{"LSST_COMPILER":"conda-system"}}}').LSST_COMPILER == 'conda-system'
  }

  def "parseImageLabels returns empty map when labels are null"() {
    expect:
    util.parseImageLabels('null') == [:]
  }

  def "parseTableDeps reads setupRequired and setupOptional product names"() {
    given:
    def table = '''\
      # comment setupRequired(ignored)
      setupRequired(utils)
      setupRequired( afw -j )
      setupOptional(sconsUtils 1.0)
      if (type == exact) {
        setupRequired(daf_butler)
      }
      setupRequired(utils)  # duplicate
    '''.stripIndent()

    expect:
    util.parseTableDeps(table) == ['utils', 'afw', 'sconsUtils', 'daf_butler']
  }

  def "parseTableDeps returns empty list for empty text"() {
    expect:
    util.parseTableDeps('') == []
  }

  def "resolveUmbrellaPackages walks deps transitively and drops unbuilt products"() {
    given:
    def tables = [
      lsst_apps: 'setupRequired(afw)\nsetupRequired(numpy)',
      afw      : 'setupRequired(utils)',
      utils    : '',
      unrelated: 'setupRequired(utils)',
    ]

    expect:
    util.resolveUmbrellaPackages('lsst_apps', tables) == ['afw', 'lsst_apps', 'utils']
  }

  def "resolveUmbrellaPackages tolerates cycles"() {
    expect:
    util.resolveUmbrellaPackages('a', [a: 'setupRequired(b)', b: 'setupRequired(a)']) == ['a', 'b']
  }

  def "resolveUmbrellaPackages returns empty when root was not built"() {
    expect:
    util.resolveUmbrellaPackages('lsst_apps', [afw: '']) == []
  }

  def "remapCoverageSources prefixes python and tests sources with the package"() {
    given:
    def xml = '''\
      <sources>
        <source>/j/ws/lsstsw/build/afw/python</source>
        <source>./python</source>
        <source>/j/ws/lsstsw/build/afw/tests</source>
        <source>/opt/lib/mypython</source>
      </sources>'''.stripIndent()

    when:
    def out = util.remapCoverageSources(xml, 'afw')

    then:
    out.count('<source>afw/python</source>') == 2
    out.contains('<source>afw/tests</source>')
    out.contains('<source>/opt/lib/mypython</source>')
  }
}
