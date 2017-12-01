import util.Plumber

def p = new Plumber(name: 'sqre/ci-ci/bench-gitlfs', dsl: this)
p.pipeline().with {
  description('Benchmark git lfs version.')

  parameters {
    stringParam('LFS_VER', '1.5.5 2.3.4', 'git lfs version(s) -- space seperated')
    stringParam('RUNS', '3', 'number of repeated benchmarking runs')
  }
}
