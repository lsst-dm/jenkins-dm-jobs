import util.StackOsMatrix

[
  [
    name: 'stack-os-matrix',
    product: 'lsst_sims lsst_distrib',
    pys: ['py2'],
  ],
  [
    name: 'stack-os-matrix-py3',
    product: 'lsst_distrib',
    pys: ['py3'],
  ],
  [
    name: 'stack-os-matrix-py2py3',
    product: 'lsst_distrib',
    pys: ['py2', 'py3'],
  ],
].each { j ->
  def stack = new StackOsMatrix(j)
  stack.build(this)
}
