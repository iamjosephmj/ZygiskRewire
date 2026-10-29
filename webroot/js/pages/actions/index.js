import { whichCurrentPage } from '../navbar.js'
import { getStrings } from '../pageLoader.js'
import { exec, toast } from '../../kernelsu.js'

async function _getMonitorState() {
  /* INFO: Deliberately uses unqualified `cat` so this status-only page
     carries no binary path references; the manager app owns all control. */
  const stateCmd = await exec('cat @@TMP_PATH@@/@@STATE_NAME@@')
  if (stateCmd.errno !== 0) {
    toast('Error getting state!')

    return;
  }

  try {
    const ModState = JSON.parse(stateCmd.stdout)
    return ModState.monitor.state
  } catch {
    return null;
  }
}

async function _updateDynamicElement() {
  const monitor_status = document.getElementById('monitor_status')
  const strings = await getStrings(whichCurrentPage())
  const monitorState = await _getMonitorState()

  if (monitorState == null) return;

  switch (monitorState) {
    case '0': monitor_status.innerHTML = strings.monitor.status.tracing; break;
    case '1': monitor_status.innerHTML = strings.monitor.status.stopping; break;
    case '2': monitor_status.innerHTML = strings.monitor.status.stopped; break;
    case '3': monitor_status.innerHTML = strings.monitor.status.exiting; break;
    default: monitor_status.innerHTML = strings.monitor.status.unknown;
  }
}

export async function loadOnce() {

}

export async function loadOnceView() {
  _updateDynamicElement()
}

export async function onceViewAfterUpdate() {
  _updateDynamicElement()
}

/* INFO: Start/stop controls were removed — the monitor is controlled
   through the manager app now. This page is status-only. */
export async function load() {
  return;
}
