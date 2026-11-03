// libs:cad-mesh (#1404): glTF/GLB, FBX (three.js loaders reused from libs:cgc-3d) and OBJ (vendored OBJLoader, three r185).
import * as THREE from 'three';
export async function load(url, format) {
  if (format === 'glb' || format === 'gltf') { const { GLTFLoader } = await import('../../cgc/vendor/GLTFLoader.js'); return (await new GLTFLoader().loadAsync(url)).scene; }
  if (format === 'fbx') { const { FBXLoader } = await import('../../cgc/vendor/FBXLoader.js'); return await new FBXLoader().loadAsync(url); }
  if (format === 'obj') {
    const { OBJLoader } = await import('./vendor/OBJLoader.js'); const o = await new OBJLoader().loadAsync(url);
    o.traverse((m) => { if (m.isMesh && !m.material.map) m.material = new THREE.MeshStandardMaterial({ color: 0xcfd4dc }); }); return o;
  }
  throw new Error('libs:cad-mesh does not read ' + format);
}
