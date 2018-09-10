import util.Common
import org.yaml.snakeyaml.Yaml
Common.makeFolders(this)

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix.yaml'))

import util.CleanBuild

[
  [
    name: 'scipipe/lsst_distrib',
    products: scipipe.canonical.products,
    skipDocs: false,
    seedJob: SEED_JOB,
  ],
  [
    name: 'scipipe/ci_hsc',
    products: 'ci_hsc',
    skipDocs: true,
    buildConfig: 'scipipe-lsstsw-ci_hsc',
    seedJob: SEED_JOB,
  ],
  [
    name: 'dax/dax_webserv',
    products: 'dax_webserv',
    skipDocs: true,
    buildConfig: 'dax-lsstsw-matrix',
    seedJob: SEED_JOB,
  ],
  [
    name: 'dax/qserv_distrib',
    products: 'qserv_distrib',
    skipDocs: true,
    buildConfig: 'dax-lsstsw-matrix',
    seedJob: SEED_JOB,
  ],
].each { j ->
  def clean = new CleanBuild(j)

  clean.build(this)
}
