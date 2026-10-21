// GPT-generated master and transparent derivative. CSS crops stay inside each
// icon tile, excluding extraction residue outside the artwork. Labels stay DOM.
const OPS6_ASSET_CROPS = Object.freeze({document:[220,158],teacher:[584,158],room:[948,158],students:[1314,158],clock:[220,489],approve:[584,489],publish:[948,489],empty:[1314,489]});
function Ops6Asset({name='document',size=40,className=''}) {
  const [x,y]=OPS6_ASSET_CROPS[name] || OPS6_ASSET_CROPS.document;
  return <span className={`ops6-asset ${className}`} aria-hidden="true" style={{width:size,height:size,borderRadius:Math.round(size*.16),display:'inline-block',overflow:'hidden',position:'relative',flexShrink:0}}><img src="assets/operations-v6/icons-transparent.png" alt="" draggable="false" decoding="async" style={{position:'absolute',maxWidth:'none',width:1774/240*size,height:887/240*size,left:-x/240*size,top:-y/240*size}}/></span>;
}
window.Ops6Asset=Ops6Asset;
