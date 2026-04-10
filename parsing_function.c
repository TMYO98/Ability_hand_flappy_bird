/**
 * Parse EMG / direct-control plot payload after enabling stream with P2.
 * Layout: "DIR" (3 bytes) + float32 LE (ch1) + float32 LE (ch2) => 11 bytes minimum.
 *
 * @param {number[] | Uint8Array} value - Raw notification from BLE (e.g. BleManagerDidUpdateValueForCharacteristic)
 * @returns {{ ch1: number, ch2: number } | null} null if header doesn't match or buffer too short
 */
function parseDirectControlEmgSamples(value) {
  const len = value.length;
  if (len < 11) return null;

  const b0 = value[0];
  const b1 = value[1];
  const b2 = value[2];
  // ASCII "DIR"
  if (b0 !== 0x44 || b1 !== 0x49 || b2 !== 0x52) return null;

  const readFloat32LE = (offset) => {
    const bytes = new Uint8Array([
      value[offset],
      value[offset + 1],
      value[offset + 2],
      value[offset + 3],
    ]);
    return new DataView(bytes.buffer).getFloat32(0, true);
  };

  return {
    ch1: readFloat32LE(3),
    ch2: readFloat32LE(7),
  };
}