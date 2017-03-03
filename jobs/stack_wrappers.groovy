import util.StackOsMatrix

[
  [
    product: 'dax_webserv',
    skip_demo: true,
  ],
  [
    product: 'lsst_distrib',
    skip_demo: false,
  ],
  [
    product: 'lsst_apps',
    skip_demo: false,
  ],
  [
    product: 'qserv_distrib',
    skip_demo: true,
  ],
  [
    product: 'lsst_py3',
    skip_demo: true,
    python: 'py3',
  ],
  [
    product: 'ci_hsc',
    skip_demo: true,
  ],
  [
    product: 'lsst_obs',
    skip_demo: true,
  ],
].each { j ->
  def stack = new StackOsMatrix(j)

  stack.build(this)
}
