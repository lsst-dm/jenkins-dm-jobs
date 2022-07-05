import util.Common
import org.yaml.snakeyaml.Yaml
Common.makeFolders(this)

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix.yaml'))

import util.CleanBuild

[
  [
    name: 'scipipe/lsst_distrib',
    products: scipipe.canonical.products,
    buildDocs: true,
    buildConfig: 'scipipe-lsstsw-lsst_distrib',
    seedJob: SEED_JOB,
  ],
  [
    name: 'scipipe/ci_hsc',
    products: 'ci_hsc',
    buildConfig: 'scipipe-lsstsw-ci_hsc',
    seedJob: SEED_JOB,
  ],
  [
    name: 'scipipe/ci_imsim',
    products: 'ci_imsim',
    buildConfig: 'scipipe-lsstsw-ci_imsim',
    seedJob: SEED_JOB,
  ],
].each { j ->
  def clean = new CleanBuild(j)

  clean.build(this)
}
