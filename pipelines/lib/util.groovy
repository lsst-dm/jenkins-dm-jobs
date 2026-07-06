import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import groovy.transform.Field
import java.security.MessageDigest

/**
 * Remove leading whitespace from a multi-line String (probably a shellscript).
 */
@NonCPS
def String dedent(String text) {
  if (text == null) {
    return null
  }
  text.replaceFirst("\n","").stripIndent()
}

/**
 * Thin wrapper around {@code sh} step that strips leading whitspace.
 */
def void posixSh(script) {
  script = dedent(script)
  sh shebangerize(script, '/bin/sh -xe')
}

/**
 * Thin wrapper around {@code sh} step that strips leading whitspace.
 */
def void bash(script) {
  script = dedent(script)
  sh shebangerize(script, '/bin/bash -xe')
}

/**
 * Prepend a shebang to a String that does not already have one.
 *
 * @param script String Text to prepend a shebang to
 * @return shebangerized String
 */
@NonCPS
def String shebangerize(String script, String prog = '/bin/sh -xe') {
  if (!script.startsWith('#!')) {
    script = "#!${prog}\n${script}"
  }

  script
}

/**
 * Hash a String using the SHA1 algorithm.
 *
 * @param path String representing the path to be hashed
 * @return hashed path String
 */
@NonCPS
def String hashpath(String path) {
  def digest = MessageDigest.getInstance('SHA-1')
  digest.update(path.bytes)
  def hashpathstr = digest.digest().encodeHex().toString()

  return hashpathstr
}

/**
 * Create a buildx builder pointing at the BuildKit sidecar socket.
 * Call once per node before any docker buildx build invocation.
 */
def void setupBuildkitBuilder() {
  sh '''
    set -eu
    if docker buildx inspect agent-builder >/dev/null 2>&1; then
      docker buildx use agent-builder
    else
      docker buildx create \
        --driver remote \
        --name agent-builder \
        --use \
        unix:///run/buildkit/buildkitd.sock
    fi

    # The remote driver does not verify connectivity at create time; the first
    # build is what actually dials the socket. The buildkitd sidecar can still
    # be starting then -- especially on the arm nodepool, which scales from zero
    # so the pod lands on a cold node while moby/buildkit is still pulling. Poll
    # until the daemon answers, otherwise the build fails with
    # "waiting for connection: context deadline exceeded".
    for i in $(seq 1 60); do
      if docker buildx inspect --bootstrap agent-builder >/dev/null 2>&1; then
        echo "buildkitd ready after attempt ${i}"
        break
      fi
      if [ "${i}" -eq 60 ]; then
        echo "buildkitd never became reachable; diagnostics follow:" >&2
        ls -la /run/buildkit || true
        docker buildx inspect --bootstrap agent-builder || true
        exit 1
      fi
      sleep 5
    done
  '''
}

/**
 * Return --cache-from and --cache-to flags for BuildKit registry cache.
 *
 * @param cacheRepo Full repo path without tag, e.g.
 *   us-central1-docker.pkg.dev/prompt-proto/buildcache/newinstall
 * @param arch Architecture suffix, e.g. amd64 or arm64
 */
def String buildkitCacheArgs(String cacheRepo, String arch, Boolean pushCache = true) {
  // --cache-from only needs read access; --cache-to needs write auth to the cache
  // registry, so gate it on pushCache (e.g. omit on NO_PUSH validation builds).
  def out = "--cache-from type=registry,ref=${cacheRepo}:cache-${arch}"
  if (pushCache) {
    out += " --cache-to type=registry,ref=${cacheRepo}:cache-${arch},mode=max"
  }
  return out
}

/**
 * Run a closure inside a Kubernetes pod using the specified container image.
 * Kubernetes-native replacement for insideDockerWrap — no Docker daemon required.
 *
 * @param p Map
 * @param p.image       String container image to run inside (required)
 * @param p.pull        Boolean set imagePullPolicy: Always (optional, default false)
 * @param p.cacheImage  String gcloud-cli image to add as a second container for cache
 *                      operations (optional). When set, a 'gcloud-cli' container is
 *                      added to the pod sharing the same workspace volumes so that
 *                      container('gcloud-cli') can be used to download/upload cache
 *                      without a separate pod and without hostPath mounts.
 * @param p.cpuRequest  String optional runner CPU request (default '8').
 * @param p.cpuLimit    String optional runner CPU limit (default '10').
 * @param p.memRequest  String optional runner memory request (default '64Gi').
 * @param p.memLimit    String optional runner memory limit (default '64Gi').
 * @param p.storage     String optional /j workspace size (default '300Gi').
 * @param p.emptyDirWorkspace Boolean optional; back /j with an emptyDir
 *                      (sizeLimit = storage) instead of a hyperdisk PVC. Use for
 *                      lightweight jobs that don't need a dedicated disk and want
 *                      a workspace below the hyperdisk-balanced 4Gi minimum
 *                      (optional, default false).
 * @param run       Closure to execute inside the container
 */
def void insideK8sContainer(Map p, Closure run) {
  requireMapKeys(p, ['image'])

  String image       = p.image
  Boolean pull       = p.pull ?: false
  String cacheImage  = p.cacheImage ?: null
  String arch        = p.arch ?: null
  String pullPolicy  = pull ? 'Always' : 'IfNotPresent'
  Boolean emptyDirWorkspace = p.emptyDirWorkspace ?: false

  def podYaml = renderPodYaml(
    image:      image,
    pullPolicy: pullPolicy,
    cacheImage: cacheImage,
    arch:       arch,
    cpuRequest: p.cpuRequest,
    cpuLimit:   p.cpuLimit,
    memRequest: p.memRequest,
    memLimit:   p.memLimit,
    storage:    p.storage,
    emptyDirWorkspace: emptyDirWorkspace,
  )

  // Surface the arch in the generated pod name so the two matrix instances are
  // distinguishable at a glance (e.g. stack-os-matrix-9465-arm64-xxxxx).  The
  // plugin sanitizes and appends random suffixes to this base name.
  def podName = "${env.JOB_BASE_NAME}-${env.BUILD_NUMBER}"
  if (arch) {
    podName = "${podName}-${arch}"
  }

  podTemplate(name: podName, yaml: podYaml) {
    node(POD_LABEL) {
      container('runner') {
        run()
      }
    }
  }
} // insideK8sContainer

/**
 * Render the agent pod YAML used by {@link #insideK8sContainer}. Pure (no
 * pipeline steps) so it is unit-testable.
 *
 * @param p Map
 * @param p.image      String runner/initContainer image (required)
 * @param p.pullPolicy String imagePullPolicy, e.g. 'Always' or 'IfNotPresent'
 * @param p.cacheImage String optional gcloud-cli sidecar image; when set, a
 *                     'gcloud-cli' container sharing the workspace volumes is added
 * @param p.arch       String optional target arch; 'arm64' pins the pod to arm
 *                     nodes (nodeSelector + toleration). Anything else schedules
 *                     on the default (x86) pool.
 * @param p.cpuRequest String optional runner CPU request (default '8').
 * @param p.cpuLimit   String optional runner CPU limit (default '10').
 * @param p.memRequest String optional runner memory request (default '64Gi').
 * @param p.memLimit   String optional runner memory limit (default '64Gi').
 * @param p.storage    String optional /j workspace ephemeral-PVC size (default '300Gi').
 * @param p.emptyDirWorkspace Boolean optional; when true /j is an emptyDir with
 *                     sizeLimit=storage instead of a hyperdisk PVC (default false).
 * @return YAML String
 */
@NonCPS
def String renderPodYaml(Map p) {
  String image      = p.image
  String pullPolicy = p.pullPolicy
  String cacheImage = p.cacheImage ?: null
  String arch       = p.arch ?: null
  // Runner resources and workspace size. Defaults are sized for a full stack
  // build; lightweight jobs (e.g. sonar-scan) can request less so the pod packs
  // onto existing capacity instead of forcing a new -- possibly stocked-out --
  // node to be scaled up.
  String cpuRequest = p.cpuRequest ?: '8'
  String cpuLimit   = p.cpuLimit ?: '10'
  String memRequest = p.memRequest ?: '64Gi'
  String memLimit   = p.memLimit ?: '64Gi'
  String storage    = p.storage ?: '300Gi'
  Boolean emptyDirWorkspace = p.emptyDirWorkspace ?: false

  // /j: the build workspace, backed by a per-build generic ephemeral volume
  // (a Hyperdisk dynamically provisioned via the hyperdisk-rwo StorageClass,
  // deleted with the pod) rather than an emptyDir. An emptyDir lives on the
  // node root disk, and the multi-GB lsstsw build filling it triggered kubelet
  // ephemeral-storage eviction of the whole agent. The c4d (x86) and c4a (arm)
  // worker machine families are Hyperdisk-only -- GCP rejects pd-balanced and
  // other pd-* disk types on them -- so the class must be hyperdisk-balanced,
  // and it must NOT be zone-restricted because the c4d pool spans
  // us-central1-a and -c (WaitForFirstConsumer binds it in the pod's zone).
  // The cluster default readOnlyRootFilesystem:true is why /j must be a
  // writable mount at all.
  //
  // emptyDirWorkspace flips /j back to an emptyDir (sizeLimit=storage) for
  // lightweight jobs that neither run a multi-GB lsstsw build nor need a
  // dedicated disk. This dodges the hyperdisk-balanced 4Gi minimum (so /j can be
  // 2Gi) and is pool-agnostic -- no PVC provisioning and no pd-* class that the
  // c4d/c4a worker pools would reject if the pod landed there.
  // /home/jenkins: gives git a writable home so it can find .gitconfig and skip getpwuid()
  def extraVolumeMounts = "    - name: j-workspace\n      mountPath: /j\n" +
                          "    - name: home-jenkins\n      mountPath: /home/jenkins\n"
  def jWorkspaceVolume  = emptyDirWorkspace ?
    "  - name: j-workspace\n    emptyDir:\n      sizeLimit: ${storage}\n" :
    "  - name: j-workspace\n" +
    "    ephemeral:\n" +
    "      volumeClaimTemplate:\n" +
    "        spec:\n" +
    "          accessModes: [ReadWriteOnce]\n" +
    "          storageClassName: hyperdisk-rwo\n" +
    "          resources:\n" +
    "            requests:\n" +
    "              storage: ${storage}\n"
  def extraVolumes      = jWorkspaceVolume +
                          "  - name: home-jenkins\n    emptyDir: {}\n"

  def volumeMountsSection = "    volumeMounts:\n" + extraVolumeMounts

  def volumesSection = "  volumes:\n" + extraVolumes

  // arm nodes are tainted kubernetes.io/arch=arm64:NoSchedule, so without both
  // the nodeSelector and the matching toleration the pod can only land on the
  // default x86 pool -- which is why aarch64 matrix builds were running on x86.
  def schedulingSection = (arch == 'arm64') ? """  nodeSelector:
    kubernetes.io/arch: arm64
  tolerations:
  - effect: NoSchedule
    key: kubernetes.io/arch
    operator: Equal
    value: arm64
""" : ''

  // Optional gcloud-cli sidecar that shares the same workspace volumes.
  // Both containers see the same /j/workspace/... so files downloaded by
  // gcloud-cli are immediately visible to the runner without any inter-pod
  // data transfer or hostPath mounts.
  def gcloudContainerSection = cacheImage ? """  - name: gcloud-cli
    image: ${cacheImage}
    imagePullPolicy: Always
    tty: true
    command: [sleep]
    args: ['99d']
    env:
    - name: HOME
      value: /home/jenkins
    securityContext:
      runAsUser: 1000
      runAsNonRoot: true
      readOnlyRootFilesystem: false
    resources:
      requests:
        cpu: 500m
        memory: 1Gi
      limits:
        cpu: 500m
        memory: 1Gi
    volumeMounts:
    - name: j-workspace
      mountPath: /j
    - name: home-jenkins
      mountPath: /home/jenkins
""" : ''

  def podYaml = """
apiVersion: v1
kind: Pod
spec:
  securityContext:
    # The /j workspace is a freshly-formatted Hyperdisk owned root:root 0755;
    # without fsGroup the uid-1000 containers (notably jnlp) cannot write it and
    # the remoting agent aborts its RWX check. fsGroup makes the kubelet chown
    # mounted volumes to this GID and set them group-writable. (An emptyDir was
    # created 0777 so this was never needed before the PVC switch.)
    fsGroup: 1000
  initContainers:
  - name: setup-home
    image: ${image}
    imagePullPolicy: ${pullPolicy}
    securityContext:
      runAsUser: 1000
      runAsNonRoot: true
    command: [sh, -c]
    args:
    - |
      printf '[user]\\n\\tname = jenkins\\n\\temail = jenkins@lsst.org\\n' > /home/jenkins/.gitconfig
    resources:
      requests:
        cpu: 100m
        memory: 128Mi
      limits:
        cpu: 100m
        memory: 128Mi
    volumeMounts:
    - name: home-jenkins
      mountPath: /home/jenkins
  containers:
  - name: jnlp
    workingDir: /j
    resources:
      requests:
        cpu: 500m
        memory: 512Mi
      limits:
        cpu: 500m
        memory: 512Mi
    volumeMounts:
    - name: j-workspace
      mountPath: /j
    - name: home-jenkins
      mountPath: /home/jenkins
  - name: runner
    image: ${image}
    imagePullPolicy: ${pullPolicy}
    tty: true
    command: [sh, -c]
    args:
    - |
      echo 'jenkins:x:1000:0:Jenkins:/home/jenkins:/bin/sh' >> /etc/passwd
      exec sleep 99d
    env:
    - name: HOME
      value: /home/jenkins
    - name: USER
      value: jenkins
    - name: LOGNAME
      value: jenkins
    securityContext:  # matches 'jenkins' user in LSST base images
      runAsUser: 1000
      runAsNonRoot: true
      readOnlyRootFilesystem: false
    resources:
      requests:
        cpu: "${cpuRequest}"
        memory: ${memRequest}
      limits:
        cpu: "${cpuLimit}"
        memory: ${memLimit}
${volumeMountsSection}${gcloudContainerSection}${volumesSection}${schedulingSection}"""

  return podYaml
} // renderPodYaml

/**
 * Parse OCI image labels from the JSON emitted by an image-inspection tool.
 * Pure (no pipeline steps) so it is unit-testable. Handles the three shapes we
 * may encounter:
 *   - skopeo inspect                -> top-level `.Labels`
 *   - crane config / docker inspect -> `.config.Labels` (or `.Config.Labels`)
 *   - `docker buildx imagetools inspect --format '{{json ....Labels}}'`
 *                                   -> a flat label map (no wrapper)
 *
 * @param json String JSON document.
 * @return Map of label name -> value (empty Map if none).
 */
@NonCPS
def Map parseImageLabels(String json) {
  // imagetools emits a bare `null` when the platform has no labels, which the
  // slurper refuses to parse; treat that (and blank output) as no labels.
  if (json == null || json.trim() in ['', 'null']) {
    return [:]
  }
  def obj = new groovy.json.JsonSlurperClassic().parseText(json)
  if (obj == null) {
    return [:]
  }
  def labels = obj.Labels ?: obj.config?.Labels ?: obj.Config?.Labels ?: obj
  return (labels ?: [:]) as Map
}

/**
 * Read OCI image labels from a registry without a Docker daemon.
 *
 * Runs `crane config` in the gcloud-cli sidecar (which ships crane), so this
 * MUST be called from inside an `insideK8sContainer` pod created with
 * `cacheImage` set (i.e. the 'gcloud-cli' container is present). crane reads
 * the image config straight from the registry over HTTP -- no `docker pull`,
 * no daemon, no outer agent. This is what lets the verify jobs run entirely
 * in-pod and drop the otherwise-idle outer idf-agent.
 *
 * scipipe release tags are multi-platform manifest indexes; crane selects a
 * platform config (the labels we read -- VERSIONDB_MANIFEST_ID, LSST_COMPILER
 * -- are identical across arches) and emits `.config.Labels`.
 *
 * @param image String fully-qualified image ref.
 * @return Map of image labels.
 */
def Map imageLabels(String image) {
  def json = ''
  container('gcloud-cli') {
    json = sh(
      returnStdout: true,
      script: "crane config ${image}",
    ).trim()
  }
  return parseImageLabels(json)
}

/**
 * Join multiple String args togther with '/'s to resemble a filesystem path.
 */
// The groovy String#join method is not working under the security sandbox
// https://issues.jenkins-ci.org/browse/JENKINS-43484
@NonCPS
def String joinPath(String ... parts) {
  String text = null

  def n = parts.size()
  parts.eachWithIndex { x, i ->
    if (text == null) {
      text = x
    } else {
      text += x
    }

    if (i < (n - 1)) {
      text += '/'
    }
  }

  return text
} // joinPath

/**
 * Serialize a Map to a JSON string and write it to a file.
 *
 * @param filename output filename
 * @param data Map to serialize
 */
@NonCPS
def dumpJson(String filename, Map data) {
  def json = new groovy.json.JsonBuilder(data)
  def pretty = groovy.json.JsonOutput.prettyPrint(json.toString())
  echo pretty
  writeFile file: filename, text: pretty
}

/**
 * Parse a JSON string.
 *
 * @param data String to parse.
 * @return Object parsed JSON object
 */
@NonCPS
def slurpJson(String data) {
  new groovy.json.JsonSlurperClassic().parseText(data)
}

/**
 * Run a command, that is assumed to return JSON, and parse the stdout.
 *
 * @param script String shell script to execute.
 * @return Object parsed JSON object
 */
def shJson(String script) {
  def stdout = sh(returnStdout: true, script: script).trim()
  slurpJson(stdout)
}

/**
 * Loads LSSTCAM test data
 * @param buildDir where to run this
 * @param testDir where to place the test data
 * @return full path of test data
 */
def loadLSSTCamTestData(
  String buildDir,
  String testDir){
  def testdata
  dir(buildDir) {
    def cwd = pwd()
    testdata = "${cwd}/${testDir}"
    dir(testdata){
      withCredentials([
        [
          $class: 'StringBinding',
          credentialsId: 'weka-bucket-secret',
          variable: 'RCLONE_CONFIG_WEKA_SECRET_ACCESS_KEY'
        ], [
          $class: 'StringBinding',
          credentialsId: 'weka-access-key',
          variable: 'RCLONE_CONFIG_WEKA_ACCESS_KEY_ID'
        ], [
          $class: 'StringBinding',
          credentialsId: 'weka-bucket-url',
          variable: 'RCLONE_CONFIG_WEKA_ENDPOINT'
        ]]){
        withEnv([
          "RCLONE_CONFIG_WEKA_TYPE=s3",
          "RCLONE_CONFIG_WEKA_PROVIDER=Other",
          "LSSTCAM_BUCKET=rubin-ci-lsst/testdata_ci_lsstcam_m49"
        ]){
          // Use the gcloud-cli sidecar already present in the builder pod.
          // dir(testdata) above sets CWD inside the shared j-workspace emptyDir,
          // so rclone writes directly into the path returned to the caller.
          container('gcloud-cli') {
            bash """
              rclone copy weka:"\${LSSTCAM_BUCKET}" .
            """
          }
        }
      }
    }
  }
  return testdata
}
/**
 * Loads Cache
 * @param buildDir where to place the loaded file
 * @param tag Which eups tag to load
 */
def loadCache(
  String buildDir,
  String tag="d_latest"
) {
  dir(buildDir) {
    def workDir = pwd()
    dir("${workDir}/ci-scripts") {
      cloneCiScripts()
    }
    withCredentials([file(
      credentialsId: 'gs-eups-push',
      variable: 'GOOGLE_APPLICATION_CREDENTIALS'
    )]) {
      withEnv([
        "SERVICEACCOUNT=${eupsServiceAccount()}",
        "DATE_TAG=${tag}",
      ]) {
        // Run in the gcloud-cli sidecar that was added to this pod by
        // insideK8sContainer when cacheImage was set.  Both containers share
        // the j-workspace emptyDir so files downloaded here are immediately
        // visible to the runner — no inter-pod hostPath mounts required.
        container('gcloud-cli') {
          bash """
          gcloud auth activate-service-account \$SERVICEACCOUNT --key-file=\$GOOGLE_APPLICATION_CREDENTIALS
          cd ${workDir}/ci-scripts
          ./loadlsststack.sh \$DATE_TAG
          """
        }
      }
    }
  }
}
/**
 * Save Cache
 * @param buildDir where to place the loaded file
 * @param tag Which eups tag to load
 */
def saveCache(
  String tag="d_latest"
) {
  def workDir = pwd()
  dir("${workDir}/ci-scripts") {
    cloneCiScripts()
  }
  withCredentials([file(
    credentialsId: 'gs-eups-push',
    variable: 'GOOGLE_APPLICATION_CREDENTIALS'
  )]) {
    withEnv([
      "SERVICEACCOUNT=${eupsServiceAccount()}",
      "DATE_TAG=${tag}",
    ]) {
      // Run in the gcloud-cli sidecar so we don't need gcloud in the LSST
      // runner image and don't need to install it via conda.
      container('gcloud-cli') {
        bash """
        gcloud auth activate-service-account \$SERVICEACCOUNT --key-file=\$GOOGLE_APPLICATION_CREDENTIALS
        cd ${workDir}/ci-scripts
        ./backuplsststack.sh \$DATE_TAG
        """
      }
    }
  }
}


def labelPod(){
  if (!fileExists('/var/run/secrets/kubernetes.io/serviceaccount/token')) {
      echo "Not a K8s Pod, skipping pod label."
      return
  }

  if (env.NODE_NAME && (env.NODE_NAME =~ /(manager|snowflake)/)) {
        echo "Skipping pod label: ${env.NODE_NAME} is a static manager/snowflake node."
        return
  }

  def JOB = env.JOB_NAME ? env.JOB_NAME.replace('/', '.') : "unknown"
    def BUILD_NUMBER = env.BUILD_NUMBER ? env.BUILD_NUMBER.toString() : "unknown"

  def labels = [
        "jenkins-job": JOB,
        "jenkins-build": BUILD_NUMBER
    ]

  def upstream = currentBuild.upstreamBuilds
  def upstreamFields = ""
  if (upstream) {
    def tJob = upstream[0].projectName.replace('/', '.')
    def tNum = upstream[0].number.toString()
    echo "Upstream Trigger: ${tJob} #${tNum}"
    labels["triggered-by"] = tJob
    labels["triggered-build"] = tNum
  }

  def jsonLabels = groovy.json.JsonOutput.toJson([metadata: [labels: labels]])
  echo "jsonLabels: ${jsonLabels}"
  writeFile file: '/tmp/patch.json', text: jsonLabels

  bash '''
  set -eu
  set +x

  SA=/var/run/secrets/kubernetes.io/serviceaccount
  [ -r "$SA/token" ] || { echo "not in k8s; skipping pod label"; exit 0; }
  NS=$(cat $SA/namespace)
  POD=${HOSTNAME}
  TOKEN=$(cat $SA/token)
  CA=$SA/ca.crt


  if curl -sS --fail \
    --cacert "$CA" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/merge-patch+json" \
    -X PATCH \
    "https://kubernetes.default.svc/api/v1/namespaces/$NS/pods/$POD" \
    --data-binary @/tmp/patch.json >/dev/null
  then
    echo "labeled for pod $NS/$POD"
  else
    echo "pod label skipped for $NS/$POD"
    exit 0
  fi
  set -x
  '''

}

/**
 * Run a lsstsw build.
 *
 * @param lsstswConfig Map
 * @param buildParams Map
 * @param wipeout Delete all existing state before starting build
 */
def lsstswBuild(
  Map lsstswConfig,
  Map buildParams,
  Boolean wipeout=false,
  Boolean fetchCache=false,
  Boolean cachelsstsw=false
) {
  validateLsstswConfig(lsstswConfig)
  def slug = lsstswConfigSlug(lsstswConfig)

  buildParams = [
    LSST_COMPILER:       lsstswConfig.compiler,
    LSST_JUNIT_PREFIX:   slug,
    LSST_PYTHON_VERSION: lsstswConfig.python,
    LSST_SPLENV_REF:     lsstswConfig.splenv_ref,
  ] + buildParams


  def run = {
    if (cachelsstsw){ // runs only if we want to cache the work
      jenkinsWrapper(buildParams)
      saveCache("d_latest")
    } // if saveCacheRun
    else {
        jenkinsWrapper(buildParams)
    } // else
  } // run
  def runDocker = {
    // Pin the inner pod to the matrix entry's arch; without this the aarch64
    // build lands on x86 because arm nodes are tainted (see renderPodYaml).
    def arch = (lsstswConfig.label == 'linux-aarch64') ? 'arm64' : 'amd64'
    insideK8sContainer(
      image: lsstswConfig.image,
      pull: true,
      arch: arch,
      // Add gcloud-cli sidecar when cache loading, saving, or test-data download
      // is needed.  All three operations share the j-workspace emptyDir so data
      // transfers happen without inter-pod hostPath mounts.
      cacheImage: (fetchCache || cachelsstsw || buildParams['CI_LSSTCAM']) ? defaultGcloudCliImage() : null,
    ) {
      try {
        if (fetchCache) {
          loadCache(slug, "d_latest")
        }
        if (buildParams['CI_LSSTCAM']) {
          buildParams['LSSTCAM_TESTDATA_DIR'] = loadLSSTCamTestData(slug, "lsstcam_testdata")
        }
        withCredentials([[
          $class: 'StringBinding',
          credentialsId: 'github-api-token-checks',
          variable: 'GITHUB_TOKEN'
        ]]){
          // dir(slug) replicates the outer dir(buildDirHash) context, which does
          // not carry across the node() boundary created by insideK8sContainer.
          dir(slug) {
            run()
          }
        } // withCredentials
      } finally {
        // Collect artifacts from the pod's own workspace.  jenkinsWrapperPost
        // must run here (not in the outer nodeWrap agent) because insideK8sContainer
        // allocates a separate pod with its own workspace; the outer agent never
        // sees the build output.
        jenkinsWrapperPost(slug)
      }
    } // insideK8sContainer
  } // runDocker

  def runEnv = { doRun ->
      // No longer need hashpath as slug is short enough
      def buildDirHash = slug
      try {
        dir(buildDirHash) {
          if (wipeout) {
            deleteDir()
          }

          try {
            timeout(time: 12, unit: 'HOURS') {
              doRun()
            } // timeout
          } catch (e) {
            if (!lsstswConfig.allow_fail) {
              throw e
            }
            echo "giving up on build but suppressing error"
            echo e.toString()
          } // try
        } // dir
      } finally {
        // For non-image builds (e.g. macOS), jenkinsWrapper ran on this same
        // agent so artifacts are here.  For image builds, artifacts are in the
        // inner pod's workspace and jenkinsWrapperPost is called inside runDocker.
        if (!lsstswConfig.image) {
          jenkinsWrapperPost(buildDirHash)
        }
      }
  } // runEnv

  def agent = lsstswConfig.label
  def task = null
  if (lsstswConfig.image) {
    task = {
      if (buildParams['CI_LSSTCAM'] && lsstswConfig.label != 'linux-64'){
        return
      }
      runEnv(runDocker)
    }
  } else {
    if (cachelsstsw || buildParams['CI_LSSTCAM']){
      // runs only if we are not running a caching job. Since this isn't on
      // docker we do not need to store cache for them.
      return
    }
    else {
      task = { runEnv(run) }
    }
  }

  nodeWrap(agent) {
    task()
  } // nodeWrap
} // lsstswBuild

/**
 * Run a build using ci-scripts/jenkins_wrapper.sh
 *
 * Required keys are listed below. Any additional keys will also be set as env
 * vars.
 * @param buildParams map
 * @param buildParams.LSST_COMPILER String
 * @param buildParams.LSST_PRODUCTS String
 * @param buildParams.LSST_REFS String
 * @param buildParams.LSST_SPLENV_REF String
 */
def void jenkinsWrapper(Map buildParams) {
  // minimum set of required keys -- additional are allowed
  requireMapKeys(buildParams, [
    'LSST_COMPILER',
    'LSST_PRODUCTS',
    'LSST_REFS',
    'LSST_SPLENV_REF',
  ])
  def scipipe = scipipeConfig()

  buildParams = [
    // XXX this should be renamed in lsstsw to make it clear that its setting a
    // github repo slug
    REPOSFILE_REPO: scipipe.repos.github_repo,
  ] + buildParams

  def cwd     = pwd()
  def homeDir = "${cwd}/home"

  try {
    dir('lsstsw') {
      cloneLsstsw()
    }

    dir('ci-scripts') {
      cloneCiScripts()
    }

    // workspace relative dir for dot files to prevent bleed through between
    // jobs and subsequent builds.
    emptyDirs([homeDir])

    // cleanup *all* conda cached package info
    [
      'lsstsw/miniconda/conda-meta',
      'lsstsw/miniconda/pkgs',
    ].each { it ->
      dir(it) {
        deleteDir()
      }
    }

    // This file is needed for conda to know it has a base environment.
    bash '''
      mkdir -p lsstsw/miniconda/conda-meta
      touch lsstsw/miniconda/conda-meta/history
    '''

    // This line uses k8s to set EUPSPKG_NJOBS
    def njobs = 16

    // Check if NODE_LABELS is set in the environment
    def nodeLabels = env.NODE_LABELS

    def buildEnv = [
      "WORKSPACE=${cwd}",
      "HOME=${homeDir}",
      "EUPS_USERDATA=${homeDir}/.eups_userdata",
      "EUPSPKG_NJOBS=${njobs}",
      "NODE_LABELS=${nodeLabels}"
    ]

    // Map -> List
    buildParams.each { pair ->
      buildEnv += pair.toString()
    }

    withEnv(buildEnv) {
      bash './ci-scripts/jenkins_wrapper.sh'
    }
  } finally {
    withEnv(["WORKSPACE=${cwd}"]) {
      bash '''
        if hash lsof 2>/dev/null; then
          Z=$(lsof -d 200 -t)
          if [[ ! -z $Z ]]; then
            kill -9 $Z
          fi
        else
          echo "lsof is missing; unable to kill rebuild related processes."
        fi

        rm -rf "${WORKSPACE}/lsstsw/stack/.lockDir"
      '''
    }
  } // try
} // jenkinsWrapper

def jenkinsWrapperPost(String baseDir = null, boolean prepOnly = false) {
  def lsstsw = 'lsstsw'

  if (baseDir) {
    lsstsw = "${baseDir}/${lsstsw}"
  }

  // note that archive does not like a leading `./`
  def lsstsw_build_dir = "${lsstsw}/build"
  def manifestPath = "${lsstsw_build_dir}/manifest.txt"
  def statusPath = "${lsstsw_build_dir}/status.yaml"
  def archive = [
    manifestPath,
    statusPath,
  ]

  def archive_exclude = []

  def record = [
    '*.log',
    '*.failed',
  ]

  def failed_record = [
          '_build.log',
          'config.log',
          'tests/.tests/pytest-*.xml',
          '*.failed',
  ]
  def failed_exclude = [
          'tests/.tests/pytest-*.xml-cov-*.xml',
  ]

  try {
    if (!prepOnly) {
      // if only prepare, skip junit
      if (fileExists(statusPath)) {
        def status = readYaml(file: statusPath)

        def products = status['built']
        // if there is a "failed_at" product, check it for a junit file too
        if (status['failed_at']) {
          products << status['failed_at']
        }

        def reports = []
        products.each { item ->
          def name = item['name']
          def xml = "${lsstsw_build_dir}/${name}/tests/.tests/pytest-${name}.xml"
          reports << xml

          record.each { pattern ->
            archive += "${lsstsw_build_dir}/${name}/**/${pattern}"
          }
        }

        if (reports) {
          // note that junit will ignore files with timestamps before the start
          // of the build
          junit([
            testResults: reports.join(', '),
            allowEmptyResults: true,
          ])

          archive += reports
        }
      } else {
        // handle case when there is no status.yaml due to timeouts
        // match logs for products that are not part of the current build
        failed_record.each { pattern ->
          archive += "${lsstsw_build_dir}/**/${pattern}"
        }
        failed_exclude.each { pattern ->
          archive_exclude += "${lsstsw_build_dir}/**/${pattern}"
        }
      }
    }
  } catch (e) {
    // As a last resort, find product build dirs with a wildcard.  This might
    // match logs for products that _are not_ part of the current build.
    record.each { pattern ->
      archive += "${lsstsw_build_dir}/**/${pattern}"
    }
    throw e
  } finally {
    archiveArtifacts([
      artifacts: archive.join(', '),
      excludes: archive_exclude.join(', '),
      allowEmptyArchive: true,
      fingerprint: true
    ])
  } // try
} // jenkinsWrapperPost

/**
 * Parse manifest id out of a manifest.txt format String.
 *
 * @param manifest.txt as a String
 * @return manifestId String
 */
@NonCPS
def String parseManifestId(String manifest) {
  def m = manifest =~ /(?m)^BUILD=(b.*)/
  m ? m[0][1] : null
}

/**
 * Validate that required parameters were passed from the job and raise an
 * error on any that are missing.
 *
 * @param rps List of required job parameters
 */
def void requireParams(List rps) {
  rps.each { it ->
    if (params.get(it) == null) {
      error "${it} parameter is required"
    }
  }
}

/**
 * Validate that required env vars were passed from the job and raise an
 * error on any that are missing.
 *
 * @param rev List of required env vars
 */
def void requireEnvVars(List rev) {
  // note that `env` isn't a map and #get doesn't work as expected
  rev.each { it ->
    if (env."${it}" == null) {
      error "${it} environment variable is required"
    }
  }
}

/**
 * Validate that map contains AT LEAST the specified list of keys and raise
 * an error on any that are missing.
 *
 * @param check Map object to inspect
 * @param key List of required map keys
 */
def void requireMapKeys(Map check, List keys) {
  keys.each { k ->
    if (! check.containsKey(k)) {
      error "${k} key is missing from Map"
    }
  }
}

/**
 * Empty directories by deleting and recreating them.
 *
 * @param dirs List of directories to empty
*/
def void emptyDirs(List eds) {
  eds.each { d ->
    dir(d) {
      deleteDir()
      // a file operation is needed to cause the dir() step to recreate the dir
      writeFile(file: '.dummy', text: '')
    }
  }
}

/**
 * Ensure directories exist and create any that are absent.
 *
 * @param dirs List of directories to ensure/create
*/
def void createDirs(List eds) {
  eds.each { d ->
    dir(d) {
      // a file operation is needed to cause the dir() step to recreate the dir
      writeFile(file: '.dummy', text: '')
    }
  }
}

/**
 * XXX this method was developed during the validate_drp conversion to pipeline
 * but is currently unusued.  It has been preserved as it might be useful in
 * other jobs.
 *
 * Write a copy of `manifest.txt`.
 *
 * @param rebuildId String `run-rebuild` build id.
 * @param filename String Output filename.
 */
def void getManifest(String rebuildId, String filename) {
  def manifest_artifact = 'lsstsw/build/manifest.txt'
  def buildJob          = 'release/run-rebuild'

  step([$class: 'CopyArtifact',
        // leading slash: CopyArtifact resolves names relative to the copying
        // job's folder, so a folder-qualified name must be made absolute.
        projectName: "/${buildJob}",
        filter: manifest_artifact,
        selector: [
          $class: 'SpecificBuildSelector',
          buildNumber: rebuildId // wants a string
        ],
      ])

  def manifest = readFile manifest_artifact
  writeFile(file: filename, text: manifest)
} // getManifest

/**
 * Run the `github-tag-release` script from `sqre-codekit` with parameters.
 *
 * Example:
 *
 *     util.githubTagRelease(
 *       options: [
 *         '--dry-run': true,
 *         '--org': 'myorg'
 *         '--manifest': 'b1234',
 *         '--eups-tag': 'v999_0_0',
 *       ],
 *       args: ['999.0.0'],
 *     )
 *
 * @param p Map
 * @param p.options Map CLI --<options>. Required. See `makeCliCmd`
 * @param p.options.'--org' String Required.
 * @param p.options.'--manifest' String Required.
 * @param p.options.'--eups-tag' String Required.
 * @param p.args List Eg., `[<git tag>]` Required.
 */
def void githubTagRelease(Map p) {
  requireMapKeys(p, [
    'args',
    'options',
  ])
  requireMapKeys(p.options, [
    '--org',
    '--manifest',
  ])

  // compute versiondb url
  def scipipe = scipipeConfig()
  def vdbUrl = "https://raw.githubusercontent.com/${scipipe.versiondb.github_repo}/main/manifests"

  // --eupstag-base-url is needed [when running under a "test" env] if git tags
  // are being generated from an existing eups tag.  If all workflows are
  // changed to git tag from a versiondb manifest prior to the build, it may be
  // removed.
  def eupsUrl = scipipe.eups.base_url
  def etbUrl = "${eupsUrl}/src/tags"

  def prog = 'github-tag-release'
  def defaultOptions = [
    '--debug': true,
    '--dry-run': true,
    '--token': '$GITHUB_TOKEN',
    '--user': 'sqreadmin',
    '--email': 'sqre-admin@lists.lsst.org',
    '--versiondb-base-url': vdbUrl,
    '--eupstag-base-url': etbUrl,
    '--allow-team': ['Data Management', 'DM Externals'],
    '--external-team': 'DM Externals',
    '--deny-team': 'DM Auxilliaries',
    '--fail-fast': true,
  ]

  runCodekitCmd(prog, defaultOptions, p.options, p.args)
} // githubTagRelease

/**
 * Run the `github-tag-teams` script from `sqre-codekit` with parameters.
 *
 * Example:
 *
 *     util.githubTagTeams(
 *       options: [
 *         '--dry-run': true,
 *         '--org': 'myorg',
 *         '--tag': '999.0.0',
 *       ],
 *       args: ['-r', 'v998.0.0.rc1']
 *     )
 *
 * @param p Map
 * @param p.options Map CLI --<options>. Required. See `makeCliCmd`
 * @param p.options.'--org' String Required.
 * @param p.options.'--tag' String|List Required.
 * @param p.args List Eg., `['-r', '<git refs>']` Optional.
 */
def void githubTagTeams(Map p) {
  requireMapKeys(p, [
    'options',
  ])
  requireMapKeys(p.options, [
    '--org',
    '--tag',
  ])
  def prog = 'github-tag-teams'
  def defaultOptions = [
    '--debug': true,
    '--dry-run': true,
    '--token': '$GITHUB_TOKEN',
    '--user': 'sqreadmin',
    '--email': 'sqre-admin@lists.lsst.org',
    '--allow-team': 'DM Auxilliaries',
    '--deny-team': 'DM Externals',
    '--ignore-existing-tag': true,
  ]

  runCodekitCmd(prog, defaultOptions, p.options, p.containsKey('args') ? p.args : null)
} // githubTagTeams

/**
 * Run the `github-get-ratelimit` script from `sqre-codekit`.
 *
 */
def void githubGetRatelimit() {
  def prog = 'github-get-ratelimit'
  def defaultOptions = [
    '--token': '$GITHUB_TOKEN',
  ]

  runCodekitCmd(prog, defaultOptions, null, null)
}

/**
 * Run a codekit cli command.
 *
 * @param prog String see `makeCliCmd`
 * @param defaultOptions Map see `makeCliCmd`
 * @param options Map see `makeCliCmd`
 * @param args List see `makeCliCmd`
 */
def void runCodekitCmd(
  String prog,
  Map defaultOptions,
  Map options,
  List args,
  Integer timelimit = 30
) {
  def cliCmd = makeCliCmd(prog, defaultOptions, options, args)

  timeout(time: timelimit, unit: 'MINUTES') {
    insideCodekit {
      bash cliCmd
    }
  }
} // runCodekitCmd

/**
 * Generate a string for executing a system command with optional flags and/or
 * arguments.
 *
 * @param prog String command to run.
 * @param defaultOptions Map command option flags.
 * @param options Map script option flags.  These are merged with
 * defaultOptions.  Truthy values are considered as an active flag while the
 * literal `true` constant indicates a boolean flag.  Falsey values result in
 * the flag being omitted.  Lists/Arrays result in the flag being specified
 * multiple times.
 * @param args List verbatium arguments to pass to command.
 * @return String complete cli command
 */
def String makeCliCmd(
  String prog,
  Map defaultOptions,
  Map options,
  List args
) {
  def useOpts = [:]

  if (defaultOptions) {
    useOpts = defaultOptions
  }
  if (options) {
    useOpts += options
  }

  cmd = [prog]

  if (useOpts) {
    cmd += mapToCliFlags(useOpts)
  }
  if (args) {
    cmd += listToCliArgs(args)
  }

  return cmd.join(' ')
} // makeCliCmd

/**
 * Run block inside a container with sqre-codekit installed and a github oauth
 * token defined as `GITHUB_TOKEN`.
 *
 * @param run Closure Invoked inside of node step
 */
def void insideCodekit(Closure run) {
  insideK8sContainer(
    image: defaultCodekitImage(),
    pull: true,
  ) {
    withGithubAdminCredentials {
      run()
    }
  } // insideK8sContainer
} // insideCodekit

/**
 * Convert a map of command line flags (keys) and values into a string suitable
 * to be passed on "the cli" to a program
 *
 * @param opt Map script option flags
 */
def String mapToCliFlags(Map opt) {
  def flags = []

  opt.each { k,v ->
    if (v) {
      if (v == true) {
        // its a boolean flag
        flags += k
      } else {
        // its a flag with an arg
        if (v instanceof List) {
          // its a flag with multiple values
          v.each { nested ->
            flags += "${k} \"${nested}\""
          }
        } else {
          // its a flag with a single value
          flags += "${k} \"${v}\""
        }
      }
    }
  }

  return flags.join(' ')
} // mapToCliFlags

/**
 * Convert a List of command line args into a string suitable
 * to be passed on "the cli" to a program
 *
 * @param args List of command arguments
 * @return String of arguments
 */
def String listToCliArgs(List args) {
  return args.collect { "\"${it}\"" }.join(' ')
}

/**
 * Run block with a github oauth token defined as `GITHUB_TOKEN`.
 *
 * @param run Closure Invoked inside of node step
 */
def void withGithubAdminCredentials(Closure run) {
  withCredentials([[
    $class: 'StringBinding',
    credentialsId: 'github-api-token-sqreadmin',
    variable: 'GITHUB_TOKEN'
  ]]) {
    run()
  } // withCredentials
}

/**
 * Run trivial execution time block
 *
 * @param run Closure Invoked inside of node step
 */
def void nodeTiny(Closure run) {
  nodeWrap('jenkins-manager') {
    timeout(time: 5, unit: 'MINUTES') {
      run()
    }
  }
}
def void buildOlderVersionMatrix(List LSSTVersions, products) {
  def matrix = [:]

  scipipe = scipipeConfig() // needed for side effects
  def lsstswConfig = scipipe.tarball.build_config[0]

  LSSTVersions.each { rubinVer ->
    matrix[rubinVer] = {
      buildOlderVersionTask(rubinVer, products, lsstswConfig)
    }
  } // loop

  parallel matrix
} // buildOlderVersionMatrix

def getNewestTag(){
  def eupsUrl = scipipe.eups.base_url
  def etbUrl = eupsUrl + "/src/tags/"
  def command = sh(
    script: 'curl -s "' + etbUrl + '" | grep -oE "v[0-9]+_[0-9]+_[0-9]+\\.list" | sed \'s/\\.list$//\' | sort -uV | tail -1',
    returnStdout: true
  ).trim()
  return command
} //getNewestTag

def getRubinEnv(String rubinVer) {
  def eupsUrl = scipipe.eups.base_url
  def etbUrl = eupsUrl + "/src/tags/" + rubinVer + ".list"
  def command = sh(script: "curl -s \"${etbUrl}\" | grep '^#CONDA_ENV=' | cut -d'=' -f2", returnStdout: true).trim()
  return command
}

def filterProducts(String rubinVer, String products) {
  def eupsUrl = scipipe.eups.base_url
  def etbUrl = eupsUrl + "/src/tags/" + rubinVer + ".list"

  def packages = sh(
    script: "curl -s \"${etbUrl}\" | grep -vE '^#' | cut -d' ' -f1",
    returnStdout: true
  ).trim().readLines()

  def pkgSet = packages as Set

  return products
    .split(/\s+/)
    .findAll { pkgSet.contains(it) }
    .join(' ')
}

def buildOlderVersionTask(String rubinVer, products, Map lsstswConfig){
  def agent = lsstswConfig.label
  def runDocker = {
    insideK8sContainer(
      image: lsstswConfig.image,
      pull: true,
    ) {
      withCredentials([[
        $class: 'StringBinding',
        credentialsId: 'github-api-token-checks',
        variable: 'GITHUB_TOKEN'
      ]]){
      stage("Load and build env"){
    def cwd     = pwd()

    // If rubinVer is set to o_latest, get the newest rubin env from eups.lsst
    if (rubinVer == "o_latest") {
      def command = getNewestTag()
      println "Latest tag: ${command}"
      rubinVer = command
    }
    def gitTag = rubinVer.replaceAll("^v","").replaceAll("_",".")
    def rubinEnvVer = getRubinEnv(rubinVer)
    def prod = filterProducts(rubinVer, products)
    println "Tag: ${rubinVer}"
    println "Rubin environment version: ${rubinEnvVer}"
    println "Products to build: ${prod}"
    dir('lsstsw') {
      cloneLsstsw()
    }
      bash """
        cd ${cwd}/lsstsw
        ./bin/deploy -v ${rubinEnvVer}
        . bin/envconfig -n lsst-scipipe-${rubinEnvVer}
        rebuild -B -r v${gitTag} -r ${gitTag} ${prod}
        """
        } // stage
      } // withCredentials
    } // insideK8sContainer
  } // runDocker

  nodeWrap(agent) {
    runDocker()
  } // nodeWrap
} // buildOlderVersionTask

/**
 * Execute a multiple multiple lsstsw builds using different configurations.
 *
 * @param matrixConfig List of lsstsw build configurations
 * @param buildParams Map of params/env vars for jenkins_wrapper.sh
 * @param wipeout Boolean wipeout the workspace build starting the build
 */
def lsstswBuildMatrix(
  List matrixConfig,
  Map buildParams,
  Boolean wipeout=false,
  Boolean loadCache=false,
  Boolean saveCache=false
) {
  def matrix = [:]

  matrixConfig.each { lsstswConfig ->
    validateLsstswConfig(lsstswConfig)
    def slug = lsstswConfigSlug(lsstswConfig)

    matrix[slug] = {
      lsstswBuild(
        lsstswConfig,
        buildParams,
        wipeout,
        loadCache,
        saveCache,
      )
    }
  }
  parallel matrix
} // lsstswBuildMatrix

/**
 * Clone lsstsw git repo
 */
def void cloneLsstsw() {
  def scipipe = scipipeConfig()

  gitNoNoise(
    url: githubSlugToUrl(scipipe.lsstsw.github_repo),
    branch: scipipe.lsstsw.git_ref,
  )
}

/**
 * Clone ci-scripts git repo
 */
def void cloneCiScripts() {
  def scipipe = scipipeConfig()

  gitNoNoise(
    url: githubSlugToUrl(scipipe.ciscripts.github_repo),
    branch: scipipe.ciscripts.git_ref,
  )
}

/**
 * Clone git repo without generating a jenkins build changelog
 */
def void gitNoNoise(Map args) {
  git([
    url: args.url,
    branch: args.branch,
  ])
}

/**
 * Checkout a git ref (branch, tag or SHA)
*/
def checkoutGitRef(String url, String ref) {
  checkout([
    $class: 'GitSCM',
    branches: [[name: ref]],
    userRemoteConfigs: [[url: url]],
    doGenerateSubmoduleConfigurations: false,
    submoduleCfg: [],
      extensions: [
          [$class: 'CloneOption', noTags: false, shallow: false]
        ],
  ])
}

/**
 * Parse products for duplicates
 *
 * @param products String of products seperated by whitespace
 * @return String of products seperated by whitespace with no duplicates
 */
def String validateProducts(String products) {
  return products.toLowerCase().split().toList().unique().join(' ')
}

/**
 * Parse yaml file into object -- parsed files are memoized.
 *
 * @param file String file to parse
 * @return yaml Object
 */
// The @Memoized decorator seems to break pipeline serialization and this
// method can not be labeled as @NonCPS.
@Field Map yamlCache = [:]
def Object readYamlFile(String file) {
  def yaml = yamlCache[file] ?: readYaml(text: readFile(file))
  yamlCache[file] = yaml
  return yaml
}

/**
 * Build a multi-configuration matrix of eups tarballs.
 *
 * Example:
 *
 *     util.buildTarballMatrix(
 *       tarballConfigs: config.tarball.build_config,
 *       parameters: [
 *         PRODUCTS: tarballProducts,
 *         SMOKE: true,
 *         RUN_SCONS_CHECK: true,
 *         PUBLISH: true,
 *       ],
 *       retries: retries,
 *     )
 *
 * @param p Map
 * @param p.tarballConfigs List
 * @param p.parameters.PRODUCTS String
 * @param p.parameters.EUPS_TAG String
 * @param p.retries Integer Defaults to `1`.
 */
def void buildTarballMatrix(Map p) {
  requireMapKeys(p, [
    'tarballConfigs',
    'parameters',
  ])
  p = [
    retries: 1,
  ] + p

  requireMapKeys(p.parameters, [
    'PRODUCTS',
    'EUPS_TAG',
  ])

  def platform = [:]

  p.tarballConfigs.each { item ->
    def displayName = item.display_name ?: item.label
    def displayCompiler = item.display_compiler ?: item.compiler

    def splenvRef = item.splenv_ref
    if (p.parameters.SPLENV_REF) {
      splenvRef = p.parameters.SPLENV_REF
    }
    def rubinEnvVer = splenvRef
    if (p.parameters.RUBINENV_VER) {
      rubinEnvVer = p.parameters.RUBINENV_VER
    }

    def slug = "miniconda${item.python}"
    slug += "-${item.miniver}-${splenvRef}"

    def tarballBuild = {
      retry(p.retries) {
        build job: 'release/tarball',
          parameters: [
            string(name: 'PRODUCTS', value: p.parameters.PRODUCTS),
            string(name: 'EUPS_TAG', value: p.parameters.EUPS_TAG),
            booleanParam(name: 'SMOKE', value: p.parameters.SMOKE),
            booleanParam(
              name: 'RUN_SCONS_CHECK',
              value: p.parameters.RUN_SCONS_CHECK
            ),
            booleanParam(name: 'PUBLISH', value: p.parameters.PUBLISH),
            booleanParam(name: 'WIPEOUT', value: false),
            string(name: 'TIMEOUT', value: item.timelimit.toString()), // hours
            string(name: 'IMAGE', value: nullToEmpty(item.image)),
            string(name: 'LABEL', value: item.label),
            string(name: 'COMPILER', value: item.compiler),
            string(name: 'PYTHON_VERSION', value: item.python),
            string(name: 'MINIVER', value: item.miniver),
            string(name: 'SPLENV_REF', value: splenvRef),
            string(name: 'RUBINENV_VER', value: rubinEnvVer),
            string(name: 'OSFAMILY', value: item.osfamily),
            string(name: 'PLATFORM', value: item.platform),
          ]
      } // retry
    }

    platform["${displayName}.${displayCompiler}.${slug}"] = {
      if (item.allow_fail) {
        try {
          tarballBuild()
        } catch (e) {
          echo "giving up on build but suppressing error"
          echo e.toString()
        }
      } else {
        tarballBuild()
      }
    } // platform
  } // each

  parallel platform
} // buildTarballMatrix

/**
 * Convert null to empty string; pass through valid strings
 *
 * @param s String string to process
 */
@NonCPS
def String nullToEmpty(String s) {
  if (!s) { s = '' }
  s
}

/**
 * Convert an empty string to null; pass through valid strings
 *
 * @param s String string to process
 */
@NonCPS
def String emptyToNull(String s) {
  if (s == '') { s = null }
  s
}

/**
 * Convert UNIX epoch (seconds) to a UTC formatted date/time string.
 * @param epoch Integer count of seconds since UNIX epoch
 * @return String UTC formatted date/time string
 */
@NonCPS
def String epochToUtc(Integer epoch) {
  def unixTime = Instant.ofEpochSecond(epoch)
  instantToUtc(unixTime)
}

/**
 * run `ltd upload` (ltd-conveyor) to push a doc build
 *
 * Runs in the caller's container -- the caller must produce `_build/html` in
 * the same pod/workspace (see documenteer.groovy). ltd-conveyor is
 * pip-installed here because the LSST release image provides python/pip but
 * not ltd-conveyor.
 *
 * ltd-conveyor talks to LTD Keeper (LTD_USERNAME/LTD_PASSWORD) and uploads via
 * presigned S3 URLs handed back by Keeper, so no raw AWS credentials are
 * needed (the old ltd-mason `ltd-mason-aws` binding is dropped).
 *
 * @param p Map
 * @param p.eupsTag String tag to setup (required). Eg.: 'current', 'b1234'
 * @param p.repoSlug String github repo slug. Eg.: 'lsst/pipelines_lsst_io'
 * @param p.ltdProduct String LTD product name (required)., Eg.: 'pipelines'
 * @param p.ltdSlug String git ref / edition slug (required)
 */
def ltdPush(Map p) {
  requireMapKeys(p, [
    'ltdSlug',
    'ltdProduct',
  ])

  withEnv([
    "LTD_HOST=https://keeper.lsst.codes",
    "LTD_PRODUCT=${p.ltdProduct}",
    "LTD_GIT_REF=${p.ltdSlug}",
  ]) {
    withCredentials([[
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'ltd-keeper',
      usernameVariable: 'LTD_USERNAME',
      passwordVariable: 'LTD_PASSWORD',
    ]]) {
      // ltd-conveyor uploads every file via a presigned S3 POST and raises
      // S3Error on the first non-success response, aborting the whole push. The
      // S3 backend intermittently returns 503 (slow down / service unavailable)
      // on a single file, so retry the upload with backoff rather than failing
      // the build on a transient hiccup. Each `ltd upload` registers a fresh LTD
      // build, so re-running is safe.
      bash '''
      source /opt/lsst/software/stack/loadLSST.bash
      pip install --user ltd-conveyor
      export PATH="${HOME}/.local/bin:${PATH}"

      attempt=1
      max_attempts=4
      while true; do
        if ltd upload \
          --product "${LTD_PRODUCT}" \
          --git-ref "${LTD_GIT_REF}" \
          --dir _build/html; then
          break
        fi
        if [ "${attempt}" -ge "${max_attempts}" ]; then
          echo "ltd upload failed after ${max_attempts} attempts" >&2
          exit 1
        fi
        echo "ltd upload attempt ${attempt} failed; retrying in $((attempt * 20))s" >&2
        sleep "$((attempt * 20))"
        attempt=$((attempt + 1))
      done
      '''
    } // withCredentials
  } //withEnv
} // ltdPush

/**
 * Convert UNIX epoch (milliseconds) to a UTC formatted date/time string.
 * @param epoch Integer count of milliseconds since UNIX epoch
 * @return String UTC formatted date/time string
 */
@NonCPS
def String epochMilliToUtc(Long epoch) {
  def unixTime = Instant.ofEpochMilli(epoch)
  instantToUtc(unixTime)
}

/**
 * Convert java.time.Instant objects to a UTC formatted date/time string.
 * @param moment java.time.Instant object
 * @return String UTC formatted date/time string
 */
@NonCPS
def String instantToUtc(Instant moment) {
  def utcFormat = DateTimeFormatter
                    .ofPattern("yyyyMMdd'T'hhmmssX")
                    .withZone(ZoneId.of('UTC') )

  utcFormat.format(moment)
}

/**
 * Run librarian-puppet on the current directory via a container
 *
 * @param cmd String librarian-puppet arguments; defaults to 'install'
 * @param tag String tag of docker image to use.
 */
def void librarianPuppet(String cmd='install', String tag='2.2.3') {
  insideK8sContainer(
    image: "lsstsqre/cakepan:${tag}",
    pull: true,
  ) {
    withEnv(["HOME=${pwd()}"]) {
      bash "librarian-puppet ${cmd}"
    }
  }
}

/**
 * run documenteer doc build
 *
 * Runs in the caller's container -- the caller must open the doc build pod
 * (see documenteer.groovy) so this build and the subsequent ltdPush share one
 * pod and thus one workspace (the produced `_build/html` must be visible to
 * ltdPush).
 *
 * @param p Map
 * @param p.docTemplateDir String path to sphinx template clone (required)
 * @param p.eupsTag String tag to setup (required)
 * @param p.eupsPath String path to EUPS installed productions (optional)
 */
def runDocumenteer(Map p) {
  requireMapKeys(p, [
    'docTemplateDir',
    'eupsTag',
  ])

  def homeDir = "${pwd()}/home"
  emptyDirs([homeDir])

  def docEnv = [
    "HOME=${homeDir}",
    "EUPS_TAG=${p.eupsTag}",
  ]

  if (p.eupsPath) {
    docEnv += "EUPS_PATH=${p.eupsPath}"
  }

  withEnv(docEnv) {
    dir(p.docTemplateDir) {
      bash '''
        source /opt/lsst/software/stack/loadLSST.bash
        dot -V
        if [ -f requirements.txt ]; then
          # allow to override doc tools
          pip install --upgrade --user --force-reinstall -r requirements.txt
        fi
        export PATH="${HOME}/.local/bin:${PATH}"
        setup -r . -t "$EUPS_TAG"
        if command -v  build-stack-docs >/dev/null 2>&1; then
          # use old documenteer 0.8 build installed from requirements.txt
          build-stack-docs -d . -v
        else
          # New documenteer 2.X build with spinxutils from stack
          stack-docs -d . -v build --disable-doxygen --disable-doxygen-conf
        fi
      '''
    } // dir
  } // withEnv
} // runDocumenteer

/**
 * run `release/run-rebuild` job and parse result
 *
 * Example:
 *
 *     manifestId = util.runRebuild(
 *       parameters: [
 *         PRODUCTS: products,
 *         BUILD_DOCS: true,
 *       ],
 *     )
 *
 * @param p Map
 * @param p.job String job to trigger. Defaults to `release/run-rebuild`.
 * @param p.parameters Map
 * @param p.parameters.REFS String Defaults to `''`.
 * @param p.parameters.PRODUCTS String Defaults to `''`.
 * @param p.parameters.BUILD_DOCS Boolean Defaults to `false`.
 * @param p.parameters.TIMEOUT String Defaults to `'12'`.
 * @param p.parameters.PREP_ONLY Boolean Defaults to `false`.
 * @param p.parameters.SPLENV_REF String Optional
 * @return manifestId String
 */
def String runRebuild(Map p) {
  def useP = [
    job: 'release/run-rebuild',
  ] + p

  useP.parameters = [
    REFS: '',  // null is not a valid value for a string param
    PRODUCTS: '',
    BUILD_DOCS: false,
    TIMEOUT: '12', // should be String
    PREP_ONLY: false,
    NO_BINARY_FETCH: true,
    PUBLISH: false,
  ] + p.parameters

  def jobParameters = [
          string(name: 'REFS', value: useP.parameters.REFS),
          string(name: 'PRODUCTS', value: useP.parameters.PRODUCTS),
          booleanParam(name: 'BUILD_DOCS', value: useP.parameters.BUILD_DOCS),
          booleanParam(name: 'NO_BINARY_FETCH', value: useP.parameters.NO_BINARY_FETCH),
          string(name: 'TIMEOUT', value: useP.parameters.TIMEOUT), // hours
          booleanParam(name: 'PREP_ONLY', value: useP.parameters.PREP_ONLY),
          booleanParam(name: 'PUBLISH', value: useP.parameters.PUBLISH),
  ]

  // Optional parameter. Set 'em if you got 'em
  if (useP.parameters.SPLENV_REF) {
    jobParameters += string(name: 'SPLENV_REF', value: useP.parameters.SPLENV_REF)
  }
  // EUPS distrib publish params -- only meaningful when PUBLISH is true.
  if (useP.parameters.EUPS_TAG) {
    jobParameters += string(name: 'EUPS_TAG', value: useP.parameters.EUPS_TAG)
  }
  if (useP.parameters.EUPSPKG_SOURCE) {
    jobParameters += string(name: 'EUPSPKG_SOURCE', value: useP.parameters.EUPSPKG_SOURCE)
  }
  if (useP.parameters.RUBINENV_VER) {
    jobParameters += string(name: 'RUBINENV_VER', value: useP.parameters.RUBINENV_VER)
  }

  def result = build(
    job: useP.job,
    parameters: jobParameters,
    wait: true,
  )

  nodeTiny {
    manifestArtifact = 'lsstsw/build/manifest.txt'

    step([$class: 'CopyArtifact',
          // leading slash: CopyArtifact resolves names relative to the copying
          // job's folder, so a folder-qualified name must be made absolute.
          projectName: "/${useP.job}",
          filter: manifestArtifact,
          selector: [
            $class: 'SpecificBuildSelector',
            buildNumber: result.id,
          ],
        ])

    def manifestId = parseManifestId(readFile(manifestArtifact))
    echo "parsed manifest id: ${manifestId}"
    return manifestId
  } // nodeTiny
} // runRebuild

/*
 * Convert github "slug" to a URL.
 *
 * @param slug String
 * @param scheme String Defaults to 'https'.
 * @return url String
 */
@NonCPS
def String githubSlugToUrl(String slug, String scheme = 'https') {
  switch (scheme) {
    case 'https':
      return "https://github.com/${slug}"
      break
    case 'ssh':
      return "ssh://git@github.com/${slug}.git"
      break
    default:
      throw new Error("unknown scheme: ${scheme}")
  }
}

/*
 * Generate a github "raw" download URL.
 *
 * @param p.slug String
 * @param p.path String
 * @param p.ref String Defaults to 'main'
 * @return url String
 */
def String githubRawUrl(Map p) {
  requireMapKeys(p, [
    'slug',
    'path',
  ])
  def useP = [
    ref: 'main',
  ] + p

  def baseUrl = 'https://raw.githubusercontent.com'
  return "${baseUrl}/${useP.slug}/${useP.ref}/${useP.path}"
}

/*
 * Generate URL to versiondb manifest file.
 *
 * @param manifestId String
 * @return url String
 */
def String versiondbManifestUrl(String manifestId) {
  def scipipe = scipipeConfig()
  return githubRawUrl(
    slug: scipipe.versiondb.github_repo,
    path: "manifests/${manifestId}.txt",
  )
}

/*
 * Generate URL to repos.yaml.
 *
 * @return url String
 */
def String reposUrl() {
  def scipipe = scipipeConfig()
  return githubRawUrl(
    slug: scipipe.repos.github_repo,
    ref: scipipe.repos.git_ref,
    path: 'etc/repos.yaml',
  )
}

/*
 * Generate URL to lsstinstall
 *
 * @return url String
 */
def String lsstinstallUrl() {
  def scipipe = scipipeConfig()
  return githubRawUrl(
    slug: scipipe.newinstall.github_repo,
    ref: scipipe.newinstall.git_ref,
    path: 'scripts/lsstinstall',
  )
}

/*
 * Generate URL to shebangtron
 *
 * @return url String
 */
def String shebangtronUrl() {
  def scipipe = scipipeConfig()
  return githubRawUrl(
    slug: scipipe.shebangtron.github_repo,
    ref: scipipe.shebangtron.git_ref,
    path: 'shebangtron',
  )
}

/*
 * Sanitize string for use as docker tag
 *
 * @param tag String
 * @return tag String
 */
@NonCPS
def String sanitizeDockerTag(String tag) {
  // is there a canonical reference for the tag format?
  // convert / to -
  tag.tr('/', '_')
}

/**
 * Derive a "slug" string from a lsstsw build configuration Map.
 *
 * @param lsstswConfig Map
 * @return slug String
 */
@NonCPS
def String lsstswConfigSlug(Map lsstswConfig) {
  def lc = lsstswConfig
  def displayName = lc.display_name ?: lc.label
  def displayCompiler = lc.display_compiler ?: lc.compiler

  // Since we use conda compilers and Python 3, leave them out.
  // "${displayName}.${displayCompiler}.py${lc.python}"
  "${displayName}"
}

/*
 * Sanitize string for use as an eups tag
 *
 * @param tag String
 * @return tag String
 */
@NonCPS
def String sanitizeEupsTag(String tag) {
  // if the git tag is an official version, starts with a number
  // but eups tag need still to have 'v' in front
  char c = tag.charAt(0)
  if ( c.isDigit() ) {
    tag = "v" + tag
  }

  // eups doesn't like dots in tags, convert to underscores
  // by policy, we're not allowing dash either
  tag.tr('.-', '_')
}

/*
 * Get scipipe config
 *
 * @return config Object
 */
def Object scipipeConfig() {
  readYamlFile('etc/scipipe/build_matrix.yaml')
}

/*
 * Get sqre config
 *
 * @return config Object
 */
def Object sqreConfig() {
  readYamlFile('etc/sqre/config.yaml')
}

/*
 * Get ap_verify config
 *
 * @return config Object
 */
def Object apVerifyConfig() {
  readYamlFile('etc/scipipe/ap_verify.yaml')
}

/*
 * Get sims config
 *
 * @return config Object
 */
def Object simsConfig() {
  readYamlFile('etc/sims/config.yaml')
}

/*
 * Get verify_drp_metrics config
 *
 * @return config Object
 */
def Object verifyDrpMetricsConfig() {
  readYamlFile('etc/scipipe/verify_drp_metrics.yaml')
}


/*
 * Get default gcloud docker image string
 *
 * @return gcloudImage String
 */
def String defaultGcloudImage() {
  def dockerRegistry = sqreConfig().gcloud.docker_registry
  "${dockerRegistry.repo}:${dockerRegistry.tag}"
}

/*
 * Get default codekit docker image string
 *
 * @return codekitImage String
 */
def String defaultCodekitImage() {
  def dockerRegistry = sqreConfig().codekit.docker_registry
  "${dockerRegistry.repo}:${dockerRegistry.tag}"
}

/*
 * Get default gcloud-cli sidecar docker image string.
 *
 * @return gcloudCliImage String
 */
def String defaultGcloudCliImage() {
  def dockerRegistry = sqreConfig().gcloudcli.docker_registry
  "${dockerRegistry.repo}:${dockerRegistry.tag}"
}

/*
 * Get the EUPS publish service account.
 *
 * @return serviceAccount String
 */
def String eupsServiceAccount() {
  sqreConfig().eups.service_account
}

/*
 * Build a BuildKit registry cache repo path for a given image name.
 *
 * @param name String cache image name, e.g. 'newinstall', 'scipipe-base'
 * @return repo String full cache repo path
 */
def String buildcacheRepo(String name) {
  "${sqreConfig().buildcache.repo_base}/${name}"
}

def Object runIndexUpdate(){
  def job = 'sqre/infra/update_indexjson'
  build(
    job: job,
    parameters:[
      string(name: 'ARCHITECTURE', value: 'linux-64'),
      string(name:'SPLENV_REF', value: scipipe.template.splenv_ref),
      string(name: 'MINI_VER', value: scipipe.template.tarball_defaults.miniver),
      booleanParam(
        name: 'NO_PUSH',
        value: scipipe.release.step.update_indexjson.no_push,
      ),],
    wait: true,
  ) // build

}

/**
 * run `release/docker/build-stack` job and parse result
 *
 * @param p.job Name of job to trigger. Defaults to
 *        `release/docker/build-stack`.
 * @param p.parameters.PRODUCTS String. Required.
 * @param p.parameters.EUPS_TAG String. Required.
 * @param p.parameters.MANIFEST_ID String. Required.
 * @param p.parameters.LSST_COMPILER String. Required.
 * @param p.parameters.NO_PUSH Boolean. Defaults to `false`.
 * @param p.parameters.TIMEOUT String. Defaults to `1'`.
 * @param p.parameters.SPLENV_REF String Optional
 * @return json Object
 */
def Object runBuildStack(Map p) {
  // validate p Map
  requireMapKeys(p, [
    'parameters',
  ])
  p = [
    job: 'release/docker/build-stack',
  ] + p

  // validate p.parameters Map
  requireMapKeys(p.parameters, [
    'PRODUCTS',
    'EUPS_TAG',
    // not required by the triggered job but as policy by this method.
    'MANIFEST_ID',
    'LSST_COMPILER',
  ])
  p.parameters = [
    NO_PUSH: false,
    TIMEOUT: '1', // should be String
    DOCKER_TAGS: '',  // null is not a valid value for a string param
  ] + p.parameters

  def jobParameters = [
    string(name: 'PRODUCTS', value: p.parameters.PRODUCTS),
    string(name: 'EUPS_TAG', value: p.parameters.EUPS_TAG),
    booleanParam(name: 'NO_PUSH', value: p.parameters.NO_PUSH),
    string(name: 'TIMEOUT', value: p.parameters.TIMEOUT),
    string(name: 'DOCKER_TAGS', value: p.parameters.DOCKER_TAGS),
    string(name: 'MANIFEST_ID', value: p.parameters.MANIFEST_ID),
    string(name: 'LSST_COMPILER', value: p.parameters.LSST_COMPILER),
  ]

  // Optional parameter. Set 'em if you got 'em
  if (p.parameters.SPLENV_REF) {
    jobParameters += string(name: 'SPLENV_REF', value: p.parameters.SPLENV_REF)
  }

  def result = build(
    job: p.job,
    parameters: jobParameters,
    wait: true
  )

  nodeTiny {
    resultsArtifact = 'results.json'

    step([
      $class: 'CopyArtifact',
      // leading slash: CopyArtifact resolves names relative to the copying
      // job's folder, so a folder-qualified name must be made absolute.
      projectName: "/${p.job}",
      filter: resultsArtifact,
      selector: [
        $class: 'SpecificBuildSelector',
        buildNumber: result.id,
      ],
    ])

    def json = readJSON(file: resultsArtifact)
    echo "parsed ${resultsArtifact}: ${json}"
    return json
  } // nodeTiny
} // runBuildStack

/**
 * Sleep to ensure s3 objects have sync'd with the EUPS_PKGROOT.
 *
 * Example:
 *
 *     util.waitForS3()
 */
def void waitForS3() {
  def scipipe = scipipeConfig()

  stage('wait for s3 sync') {
    sleep(time: scipipe.release.s3_wait_time, unit: 'MINUTES')
  }
} // waitForS3

/**
 * Invoke block with eups related env vars.
 *
 * Example:
 *
 *     util.withEupsEnv {
 *       util.bash './dostuff.sh'
 *     }
 *
 * @param run Closure Invoked inside of wrapper container
 */
def void withEupsEnv(Closure run) {
  def scipipe = scipipeConfig()

  def baseUrl = scipipe.eups.base_url
  def s3Bucket = scipipe.eups.s3_bucket
  def gsBucket = scipipe.eups.gs_bucket
  withEnv([
    "EUPS_S3_BUCKET=${s3Bucket}",
    "EUPS_GS_BUCKET=${gsBucket}",
    "EUPS_BASE_URL=${baseUrl}",
  ]) {
    run()
  }
} // withEupsEnv

/**
 * Create/update a clone of an lfs enabled git repo.
 *
 * Example:
 *
 *     util.checkoutLFS(
 *       githubSlug: 'foo/bar',
 *       gitRef: 'main',
 *     )
 *
 * @param p Map
 * @param p.gitRepo String github repo slug
 * @param p.gitRef String git ref to checkout. Defaults to `main`
 */
def void checkoutLFS(Map p) {
  requireMapKeys(p, [
    'githubSlug',
    'gitRef',
  ])
  p = [
    gitRef: 'main',
  ] + p

  def gitRepo = githubSlugToUrl(p.githubSlug)

  // Must be called from inside an insideK8sContainer pod whose image provides
  // git-lfs (via loadLSST.bash). The clone and the lfs pull have to run in the
  // same pod workspace: a separate pod gets its own emptyDir /j and cannot see a
  // clone made on the outer agent (this is what broke ap_verify/verify_drp).
  checkoutGitRef(gitRepo, p.gitRef)

  try {
    bash('''
      source /opt/lsst/software/stack/loadLSST.bash
      git lfs install --skip-repo
      git lfs pull origin
    ''')
  } finally {
    // try not to break jenkins clone mangement
    bash 'rm -f .git/hooks/post-checkout'
  }
} // checkoutLFS

/**
 * Download URL resource and write it to disk.
 *
 * Example:
 *
 *     util.downloadFile(
 *       url: 'https://example.org/foo/bar.baz',
 *       destFile: 'foo/bar.baz',
 *     )
 *
 * @param p Map
 * @param p.url String URL to fetch
 * @param p.destFile String path to write downloaded file
 */
def void downloadFile(Map p) {
  requireMapKeys(p, [
    'url',
    'destFile',
  ])

  writeFile(file: p.destFile, text: new URL(p.url).getText())
}

/**
 * Download `manifest.txt` from `lsst/versiondb`.
 *
 * Example:
 *
 *     util.downloadManifest(
 *       destFile: 'foo/manifest.txt',
 *       manifestId: 'b1234',
 *     )
 *
 * @param p Map
 * @param p.destFile String path to write downloaded file
 * @param p.manifestId String manifest build id aka bNNNN
 */
def void downloadManifest(Map p) {
  requireMapKeys(p, [
    'destFile',
    'manifestId',
  ])

  def manifestUrl = versiondbManifestUrl(p.manifestId)
  downloadFile(
    url: manifestUrl,
    destFile: p.destFile,
  )
}

/**
 * Download a copy of `repos.yaml`
 *
 * Example:
 *
 *     util.downloadRepos(
 *       destFile: 'foo/repos.yaml',
 *     )
 *
 * @param p Map
 * @param p.destFile String path to write downloaded file
 */
def void downloadRepos(Map p) {
  requireMapKeys(p, [
    'destFile',
  ])

  def reposUrl = reposUrl()
  downloadFile(
    url: reposUrl,
    destFile: p.destFile,
  )
}

/**
 * Collect artifacts
 *
 * Example:
 *
 *     // note: the whitespace is needed to prevent the example from exiting
 *     // the comment block -- not needed in real code
 *     util.record([
 *       "${runDir}/** /*.log",
 *       "${runDir}/** /*.json",
 *     ])
 *
 * @param archiveDirs List paths to be collected.
 */
def void record(List archiveDirs) {
  archiveDirs = relPath(pwd(), archiveDirs)

  archiveArtifacts([
    artifacts: archiveDirs.join(', '),
    excludes: '**/*.dummy',
    allowEmptyArchive: true,
    fingerprint: true
  ])
} // record

/**
 * Relativize a list of paths.
 *
 * Example:
 *
 *     util.relPath(pwd(), [
 *       "/foo/bar/baz/bonk",
 *       "/foo/bar/baz/quix",
 *     ])
 *
 * @param relativeToDir String base path
 * @param path List paths to be relativized
 * @return List of relativized paths
 */
def List relPath(String relativeToDir, List paths) {
  // convert to relative paths
  // https://gist.github.com/ysb33r/5804364
  def rootPath = Path.of(relativeToDir)
  return paths.collect { it ->
    // skip non-rel paths
    if (!it.startsWith('/')) { return it }
    rootPath.relativize(Path.of(it)).toString()
  }
} // relPath

/**
 * Relativize a list of paths.
 *
 * Example:
 *
 *     util.xz([
 *       '** /*.foo',
 *       '** /*.bar',
 *     ])
 *
 * @param patterns List of file patterns to compress
 * @return List of compressed files
 */
def List xz(List patterns) {
  patterns = relPath(pwd(), patterns)
  def files = patterns.collect { g -> findFiles(glob: g) }.flatten()
  def targetFile = 'compress_files.txt'
  writeFile(file: targetFile, text: files.join("\n") + "\n")

  // compressing an example hsc output file
  // (cmd)       (ratio)  (time)
  // xz -T0      0.183    0:20
  // xz -T0 -9   0.180    1:23
  // xz -T0 -9e  0.179    1:28

  // compress but do not remove original file
  util.bash "xz -T0 -9ev --keep --files=${targetFile}"
  return files.collect { f -> "${f}.xz" }
}

/**
 * Collect junit reports
 *
 * Example:
 *
 *     // note: the whitespace is needed to prevent the example from exiting
 *     // the comment block -- not needed in real code
 *     util.junit([
 *       "${runDir}/** /pytest-*.xml",
 *     ])
 *
 * @param testResults List paths to be collected.
 */
def void junit(List testResults) {
  testResults = relPath(pwd(), testResults)

  junit([
    testResults: testResults.join(', '),
    allowEmptyResults: true,
  ])
} // junit

/**
 * push results to squash using dispatch-verify.
 *
 * Example:
 *
 *     util.runDispatchVerify(
 *       runDir: runDir,
 *       lsstswDir: lsstswDir,
 *       datasetName: datasetName,
 *       resultFile: resultFile,
 *     )
 *
 * @param p Map
 * @param p.runDir String
 * @param p.lsstswDir String Path to (the fake) lsstsw dir
 * @param p.datasetName String The dataset name. Eg., validation_data_cfht
 * @param p.resultFile String [JSON] file to push to squash.
 */
def void runDispatchVerify(Map p) {
  util.requireMapKeys(p, [
    'runDir',
    'lsstswDir',
    'datasetName',
    'resultFile',
    'squashUrl',
  ])

  def run = {
    util.bash '''
      set +o xtrace
      source /opt/lsst/software/stack/loadLSST.bash
      setup verify
      set -o xtrace

      dispatch_verify.py \
        --env jenkins \
        --lsstsw "$LSSTSW_DIR" \
        --url "$SQUASH_URL" \
        --user "$SQUASH_USER" \
        --password "$SQUASH_PASS" \
        "$RESULT_FILE"
    '''
  } // run

  /*
  These are already present under pipeline:
  - BUILD_ID
  - BUILD_URL

  This var was defined automagically by matrixJob and now must be manually
  set:
  - dataset
  */
  withEnv([
    "LSSTSW_DIR=${p.lsstswDir}",
    "dataset=${p.datasetName}",
    "SQUASH_URL=${p.squashUrl}",
    "RESULT_FILE=${p.resultFile}",
  ]) {
    withCredentials([[
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'squash-api-user',
      usernameVariable: 'SQUASH_USER',
      passwordVariable: 'SQUASH_PASS',
    ]]) {
      dir(p.runDir) {
        run()
      }
    } // withCredentials
  } // withEnv
} // runDispatchVerify

/**
 * Convert Gen 3 results into a form suitable for dispatch-verify.
 *
 * The output files are placed in runDir.
 *
 * Example:
 *
 *     util.runGen3ToJob(
 *       runDir: runDir,
 *       gen3Dir: gen3Dir,
 *       collectionName: collectionName,
 *       namespace: "",
 *       datasetName: datasetName,
 *     )
 *
 * @param p Map
 * @param p.runDir String
 * @param p.gen3Dir String Path to the Gen 3 repository
 * @param p.collectionName String The collection to search for metrics.
 * @param p.namespace String The metrics namespace to filter by, e.g. validate_drp, or "" for all metrics.
 * @param p.datasetName String The dataset name. Eg., validation_data_cfht
 */
def void runGen3ToJob(Map p) {
  util.requireMapKeys(p, [
    'gen3Dir',
    'collectionName',
    'namespace',
    'datasetName',
  ])

  def run = {
    util.bash '''
      set +o xtrace
      source /opt/lsst/software/stack/loadLSST.bash
      setup verify
      set -o xtrace

      if [[ -n $METRIC_NAMESPACE ]]
        then gen3_to_job.py \
          "$REPO_DIR" \
          "$OUTPUT_COLLECTION" \
          --metrics_package "$METRIC_NAMESPACE" \
          --dataset_name "$dataset"
        else gen3_to_job.py \
          "$REPO_DIR" \
          "$OUTPUT_COLLECTION" \
          --dataset_name "$dataset"
      fi
    '''
  } // run

  /*
  These are already present under pipeline:
  - BUILD_ID
  - BUILD_URL

  This var was defined automagically by matrixJob and now must be manually
  set:
  - dataset
  */
  withEnv([
    "REPO_DIR=${p.gen3Dir}",
    "OUTPUT_COLLECTION=${p.collectionName}",
    "METRIC_NAMESPACE=${p.namespace}",
    "dataset=${p.datasetName}",
  ]) {
    dir(p.runDir) {
      run()
    }
  } // withEnv
} // runGen3ToJob

/**
 * push results to sasquatch using verify_to_sasquatch.
 *
 *     util.runVerifyToSasquatch(
 *       runDir: runDir,
 *       gen3Dir: gen3Dir,
 *       collectionName: collectionName,
 *       namespace: "lsst.example",
 *       datasetName: "ci_example",
 *       sasquatchUrl: util.sqreConfig().sasquatch.url,
 *       branchRefs: "tickets/DM-12345 tickets/DM-67890",
 *       pipeline: "SingleFrame.yaml",
 *     )
 * @param p Map
 * @param p.runDir String
 * @param p.gen3Dir String Path to the Gen 3 repository
 * @param p.collectionName String The collection to search for metrics.
 * @param p.namespace String The Sasquatch namespace to push to, e.g., lsst.dm.
 * @param p.datasetName String The dataset name. Eg., validation_data_cfht
 * @param p.sasquatchUrl String The URL to the Sasquatch REST proxy.
 * @param p.branchRefs String The branch(es) used in the run, as a space-delimited string (optional).
 * @param p.pipeline String The pipeline used in the run (optional).
 */
def void runVerifyToSasquatch(Map p) {
  util.requireMapKeys(p, [
    'runDir',
    'gen3Dir',
    'collectionName',
    'namespace',
    'datasetName',
    'sasquatchUrl',
  ])

  def run = {
    util.bash '''
      set +o xtrace
      source /opt/lsst/software/stack/loadLSST.bash
      setup analysis_tools
      set -o xtrace

      verify_to_sasquatch.py \
          "$REPO_DIR" \
          "$OUTPUT_COLLECTION" \
          --dataset "$dataset" \
          --url "$SASQUATCH_URL" \
          --namespace "$SASQUATCH_NAMESPACE" \
          --extra "ci_id=$BUILD_ID" \
          --extra "ci_url=$BUILD_URL" \
          --extra "ci_name=$JOB_NAME" \
          --extra "ci_refs=$JOB_REFS" \
          --extra "pipeline=$JOB_PIPELINE"
    '''
  } // run

  /*
  These are already present under pipeline:
  - BUILD_ID
  - BUILD_URL
  - JOB_NAME

  This var was defined automagically by matrixJob and now must be manually
  set:
  - dataset
  */
  withEnv([
    "REPO_DIR=${p.gen3Dir}",
    "OUTPUT_COLLECTION=${p.collectionName}",
    "SASQUATCH_NAMESPACE=${p.namespace}",
    "dataset=${p.datasetName}",
    "SASQUATCH_URL=${p.sasquatchUrl}",
    "JOB_REFS=${p.containsKey('branchRefs') ? p.branchRefs : ''}",
    "JOB_PIPELINE=${p.containsKey('pipeline') ? p.pipeline : ''}",
  ]) {
    // TODO: need Sasquatch authentication eventually; verify_to_sasquatch.py takes a --token arg
    // withCredentials([[
    //   $class: 'UsernamePasswordMultiBinding',
    //   credentialsId: 'squash-api-user',
    //   usernameVariable: 'SQUASH_USER',
    //   passwordVariable: 'SQUASH_PASS',
    // ]]) {
      dir(p.runDir) {
        run()
      }
    // } // withCredentials
  } // withEnv
} // runDispatchVerify

/**
 * Create a "fake" lsstsw-ish dir structure as expected by
 * `dispatch-verify.py`, which includes a `manifest.txt` and a copy of
 * `repos.yaml`.
 *
 * Example:
 *
 *     util.createFakeLsstswClone(
 *       fakeLsstswDir: fakeLsstswDir,
 *       manifestId: manifestId,
 *     )
 *
 * @param p Map
 * @param p.fakeLsstswDir String dir path
 * @param p.manifestId String versiondb manifest id
 */
def void createFakeLsstswClone(Map p) {
  requireMapKeys(p, [
    'fakeLsstswDir',
    'manifestId',
  ])

  def fakeLsstswDir    = p.fakeLsstswDir
  def fakeManifestDir  = "${fakeLsstswDir}/build"
  def fakeManifestFile = "${fakeManifestDir}/manifest.txt"
  def fakeReposDir     = "${fakeLsstswDir}/etc"
  def fakeReposFile    = "${fakeReposDir}/repos.yaml"

  emptyDirs([
    fakeManifestDir,
    fakeReposDir,
  ])

  downloadManifest(
    destFile: fakeManifestFile,
    manifestId: p.manifestId,
  )
  downloadRepos(destFile: fakeReposFile)
} // createFlakeLsstwClone

/**
 * Validate that a map has the minimum required set of keys for an lsstsw
 * build env configuration.
 *
 * Example:
 *
 *     util.validateLsstswConfig(lsstswConfig)
 *
 * @param p Map
 */
def void validateLsstswConfig(Map conf) {
  requireMapKeys(conf, [
    'compiler',
    'image',
    'label',
    'python',
    'splenv_ref',
  ])
}

/**
 * If running on kubernetes, report basic information about the k8s pod.
 *
 * Example:
 *
 *     util.printK8sVars()
 *
 */
def void printK8sVars() {
  // env.getEnvronment() returns vars groovy will set but not the current node env
  // System.getenv() returns the manager's env
  // env.<foo> works as this uses magic to check the actual env

  // test to see if the agent has k8s env vars
  if (env.K8S_NODE_NAME) {
    echo 'agent appears to be running on kubernetes...'
    // if so, list them using a shell as there is currently no other practical
    // way to iterate over the complete set of env vars.
    bash 'printenv | grep ^K8S_ | sort'
  }
}

/**
 * Run generic block
 *
 * Example:
 *
 *     util.nodeWrap { ... }
 *
 * @param run Closure Invoked inside of node step
 */
def void nodeWrap(Closure run) {
  nodeWrap(null) { run() }
}

/**
 * Run generic block
 *
 * Example:
 *
 *     util.nodeWrap('linux-64') { ... }
 *
 * @param label String Label expression
 * @param run Closure Invoked inside of node step
 */
def void nodeWrap(String label, Closure run) {
  node(label) {
    printK8sVars()
    labelPod()
    run()
  }
}

/**
 * Run sonar-scanner across every package in the current workspace's lsstsw/build/
 * tree, then run the umbrella scan. Intended to be called from inside lsstswBuild
 * (cache path, linux-64) after saveCache() has uploaded the tarball.
 *
 * @param args.eupsTag    String  EUPS tag, used as projectVersion (e.g. 'w_2026_21')
 * @param args.envPrefix  String  '' on prod, 'dev-' on dev (from SONAR_ENV_PREFIX)
 */
def sonarScanWorkspace(Map args) {
  String eupsTag = args.eupsTag
  String envPrefix = args.envPrefix ?: ''
  String scannerHome = tool 'sonar-scanner'
  String statusFile = "${env.WORKSPACE}/sonar-scan-status.csv"

  writeFile file: statusFile, text: "package,status,reason\n"

  def packages = sh(
    returnStdout: true,
    script: 'ls -1 lsstsw/build/ 2>/dev/null | sort',
  ).trim().split('\n').findAll { it && !it.startsWith('.') }

  echo "sonarScanWorkspace: found ${packages.size()} packages"

  try {
    // Cap each scanner JVM's heap so a chunk of 8 running in parallel fits the
    // pod's memory limit (default JVM ergonomics would size each heap to ~25%
    // of the container limit and collectively OOM). The umbrella scan sets its
    // own larger -Xmx4g inside sonarScanUmbrella and is not affected.
    withEnv(['SONAR_SCANNER_OPTS=-Xmx1g']) {
      packages.collate(8).each { chunk ->
        def scans = [:]
        chunk.each { pkg ->
          scans["scan ${pkg}"] = {
            sonarScanPackage(
              pkg: pkg,
              eupsTag: eupsTag,
              envPrefix: envPrefix,
              scannerHome: scannerHome,
              statusFile: statusFile,
            )
          }
        }
        parallel scans
      }
    }

    sonarScanUmbrella(
      eupsTag: eupsTag,
      envPrefix: envPrefix,
      scannerHome: scannerHome,
    )
  } finally {
    dir(env.WORKSPACE) {
      archiveArtifacts artifacts: 'sonar-scan-status.csv', allowEmptyArchive: true
    }
  }
}

/**
 * Run sonar-scanner against a single EUPS package directory.
 *
 * @param args.pkg            String  package directory name under lsstsw/build/
 * @param args.eupsTag        String  EUPS tag (e.g. 'w_2026_21'), used as projectVersion
 * @param args.envPrefix      String  env-scoped prefix ('' on prod, 'dev-' on dev)
 * @param args.scannerHome    String  filesystem path returned by `tool 'sonar-scanner'`
 * @param args.statusFile     String  CSV path appended to with "<pkg>,OK,"
 */
def sonarScanPackage(Map args) {
  String pkg = args.pkg
  String eupsTag = args.eupsTag
  String envPrefix = args.envPrefix ?: ''
  String scannerHome = args.scannerHome
  String statusFile = args.statusFile
  String projectKey = "${envPrefix}${pkg}"
  String pkgDir = "lsstsw/build/${pkg}"

  try {
    dir(pkgDir) {
      def covPaths = sh(
        returnStdout: true,
        script: '''find . -maxdepth 6 \\( \
            -name 'coverage.xml' \
            -o -name 'pytest-coverage.xml' \
            -o -path '*/.tests/pytest-coverage.xml' \
            -o -path '*/.tests/pytest-*.xml-cov-*.xml' \
            -o -path '*/.tests/pytest-*-cov.xml' \
            -o -path '*/.tests/*-cov.xml' \
          \\) 2>/dev/null | sort -u''',
      ).trim()
      def junitPaths = sh(
        returnStdout: true,
        script: '''find . -maxdepth 6 \\( \
            -path '*/.tests/pytest-*.xml' \
            -o -path '*/.tests/junit*.xml' \
          \\) 2>/dev/null | grep -v '\\.xml-cov-' | grep -v -- '-cov\\.xml$' | grep -v 'pytest-coverage\\.xml$' | sort -u''',
      ).trim()

      // Coverage XMLs from the build agent embed absolute <source> paths (e.g.
      // /j/workspace/stack-os-matrix/linux-9-x86/lsstsw/build/<pkg>/python) that
      // don't exist on the scan agent. Rewrite them to relative paths so SonarQube
      // resolves <class filename="..."> entries against projectBaseDir (= pkgDir).
      if (covPaths) {
        sh """
          for f in ${covPaths.replaceAll('\\n', ' ')}; do
            echo "[sonar:${pkg}] rewriting <source> in \$f"
            sed -i -E 's#<source>[^<]+/(python|tests)</source>#<source>./\\1</source>#g' "\$f"
            echo "[sonar:${pkg}] post-rewrite sources:"
            grep '<source>' "\$f" | head -5
          done
        """
      }

      echo "[sonar:${pkg}] coverage XMLs found: ${covPaths ? covPaths.split('\\n').size() : 0}"
      if (covPaths)   { echo "[sonar:${pkg}] coverage:\n${covPaths}" }
      echo "[sonar:${pkg}] junit XMLs found: ${junitPaths ? junitPaths.split('\\n').size() : 0}"
      if (junitPaths) { echo "[sonar:${pkg}] junit:\n${junitPaths}" }

      def extraProps = []
      if (covPaths) {
        extraProps << "-Dsonar.python.coverage.reportPaths=${covPaths.replaceAll('\\n', ',')}"
      }
      if (junitPaths) {
        extraProps << "-Dsonar.python.xunit.reportPath=${junitPaths.split('\\n')[0]}"
      }

      // Narrow sonar.sources to python/ + tests/ to match codecov's scope.
      // Fall back to '.' for packages with neither directory.
      String sonarSources = sh(
        returnStdout: true,
        script: '''
          srcs=""
          [ -d python ] && srcs="python"
          [ -d tests ] && srcs="${srcs:+$srcs,}tests"
          [ -z "$srcs" ] && srcs="."
          echo "$srcs"
        ''',
      ).trim()

      // The scan workspace reaches the cache via a `lsstsw` -> `cache-load/lsstsw`
      // symlink, so CWD here is the symlink path. SonarQube indexes source files
      // keyed under projectBaseDir, but the Cobertura sensor canonicalizes the
      // coverage report's paths (resolving the symlink) to the real cache-load
      // path. If base dir stays the symlink path the two key sets never match and
      // coverage silently records 0.0%. Pin projectBaseDir to the canonical path
      // (`pwd -P`) so indexed sources and resolved coverage share one real path.
      withSonarQubeEnv('lsst-sonarqube') {
        sh """
          ${scannerHome}/bin/sonar-scanner \\
            -Dsonar.projectKey=${projectKey} \\
            -Dsonar.projectName=${pkg} \\
            -Dsonar.projectVersion=${eupsTag} \\
            -Dsonar.projectBaseDir="\$(pwd -P)" \\
            -Dsonar.sources=${sonarSources} \\
            -Dsonar.python.version=3 \\
            -Dsonar.sourceEncoding=UTF-8 \\
            -Dsonar.exclusions='**/doc/**,**/.eupspkg/**,**/build/**' \\
            ${extraProps.join(' ')}
        """
      }
    }
    sh "echo '${pkg},OK,' >> ${statusFile}"
  } catch (Exception e) {
    echo "sonar-scan FAILED for ${pkg}: ${e.message}"
  }
}

/**
 * Run a single umbrella sonar-scanner over the entire lsstsw/build/ tree
 * to populate the lsst_distrib SonarQube project.
 *
 * @param args.eupsTag        String  EUPS tag, used as projectVersion
 * @param args.envPrefix      String  '' or 'dev-'
 * @param args.scannerHome    String  filesystem path returned by `tool 'sonar-scanner'`
 */
def sonarScanUmbrella(Map args) {
  String eupsTag = args.eupsTag
  String envPrefix = args.envPrefix ?: ''
  String scannerHome = args.scannerHome
  String projectKey = "${envPrefix}lsst_distrib"

  dir('lsstsw/build') {
    withEnv(['SONAR_SCANNER_OPTS=-Xmx4g']) {
      withSonarQubeEnv('lsst-sonarqube') {
        sh """
          ${scannerHome}/bin/sonar-scanner \\
            -Dsonar.projectKey=${projectKey} \\
            -Dsonar.projectName=lsst_distrib \\
            -Dsonar.projectVersion=${eupsTag} \\
            -Dsonar.sources=. \\
            -Dsonar.python.version=3 \\
            -Dsonar.sourceEncoding=UTF-8 \\
            -Dsonar.scm.disabled=true \\
            -Dsonar.exclusions='**/tests/**,**/doc/**,**/.eupspkg/**,**/build/**,**/.git/**'
        """
      }
    }
  }
}

return this;
