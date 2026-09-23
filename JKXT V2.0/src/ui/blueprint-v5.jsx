// V4 blueprint assets are decorative. All controls remain real, accessible DOM.
const BLUEPRINT_ASSETS = Object.freeze({risk:[14.8,20],staff:[50.5,20],notice:[87.1,20],teacher:[14.4,76],room:[50.5,76],calendar:[87.1,76]});
function BlueprintAsset({name,size=40,className=''}) {
  if (name === 'empty') return <img className={`bp-asset bp-asset--empty ${className}`} src="assets/blueprint-v5/empty-transparent.png" width={size} height={Math.round(size*2/3)} alt="" aria-hidden="true" decoding="async" />;
  const position = BLUEPRINT_ASSETS[name];
  if (!position) return null;
  return <span aria-hidden="true" className={`bp-asset bp-asset--symbol ${className}`} style={{width:`var(--bp-symbol-size, ${size}px)`,height:`var(--bp-symbol-size, ${size}px)`,backgroundPosition:`${position[0]}% ${position[1]}%`}} />;
}

function BlueprintLayer({kind,title,description,onClose,children,footer,busy=false,returnFocusRef,className=''}) {
  const panelRef=React.useRef(null);
  const layerRef=React.useRef(null);
  const closeRef=React.useRef(onClose);
  const busyRef=React.useRef(busy);
  const mountedRef=React.useRef(false);
  const closingRef=React.useRef(false);
  const exitSequenceRef=React.useRef(0);
  const cancelExitRef=React.useRef(null);
  const cancelEnterRef=React.useRef(null);
  const restoreFocusRef=React.useRef(returnFocusRef);
  restoreFocusRef.current=returnFocusRef;
  closeRef.current=onClose; busyRef.current=busy;
  const [host]=React.useState(()=>document.createElement('div'));
  const labelId=React.useId();
  const descriptionId=React.useId();
  const cancelExit=React.useCallback(()=>{
    exitSequenceRef.current+=1;
    closingRef.current=false;
    const cancel=cancelExitRef.current;
    cancelExitRef.current=null;
    cancel?.();
  },[]);
  const requestClose=React.useCallback(()=>{
    if(busyRef.current || closingRef.current || !mountedRef.current)return;
    closingRef.current=true;
    const sequence=++exitSequenceRef.current;
    // Do not let the existing entrance and the requested exit own the same panel.
    cancelEnterRef.current?.();
    cancelEnterRef.current=null;
    let completed=false;
    const finish=()=>{
      completed=true;
      if(sequence!==exitSequenceRef.current)return;
      cancelExitRef.current=null;
      if(!mountedRef.current)return;
      if(busyRef.current){closingRef.current=false;return;}
      // Keep the successful close locked until unmount, including sync reduced mode.
      closeRef.current?.();
    };
    if(window.JKXTMotion?.surface){
      const cleanup=window.JKXTMotion.surface(panelRef.current,{kind,exit:true,onComplete:finish});
      // Reduced motion completes synchronously; do not retain an obsolete cleanup.
      if(!completed)cancelExitRef.current=cleanup;
    }else finish();
  },[kind]);
  React.useLayoutEffect(()=>{
    mountedRef.current=true;
    const previousFocus=document.activeElement;
    host.className='bp-portal';
    document.body.appendChild(host);
    const panel=panelRef.current;
    const sharedLayer=window.JKXTDialogLayers?.register(panel,host,()=>restoreFocusRef.current?.current);
    layerRef.current=sharedLayer || null;
    const isTop=()=>sharedLayer ? sharedLayer.isTop() : !panel.closest('[inert]');
    // Standalone canvas previews may omit the shared overlay bundle.
    const siblings=sharedLayer?[]:Array.from(document.body.children).filter(node=>node!==host && !['SCRIPT','STYLE','LINK'].includes(node.tagName));
    const previous=siblings.map(node=>({node,inert:node.inert,aria:node.getAttribute('aria-hidden')}));
    previous.forEach(({node})=>{node.inert=true;node.setAttribute('aria-hidden','true');});
    const previousOverflow=document.body.style.overflow;
    document.body.style.overflow='hidden';
    const focusable=()=>Array.from(panel.querySelectorAll('button:not(:disabled),a[href],input:not(:disabled),textarea:not(:disabled),select:not(:disabled),[tabindex]:not([tabindex="-1"])')).filter(node=>node.getClientRects().length && !node.closest('[inert]'));
    panel.focus({preventScroll:true});
    const onKey=event=>{
      if(!isTop())return;
      if(event.key==='Escape') {event.preventDefault();event.stopImmediatePropagation();requestClose();return;}
      if(event.key!=='Tab')return;
      const list=focusable();const index=list.indexOf(document.activeElement);
      if(!list.length){event.preventDefault();panel.focus();return;}
      if(event.shiftKey && index<=0){event.preventDefault();list[list.length-1].focus();}
      else if(!event.shiftKey && (index<0 || index===list.length-1)){event.preventDefault();list[0].focus();}
    };
    const containFocus=event=>{if(isTop() && !panel.contains(event.target))panel.focus({preventScroll:true});};
    document.addEventListener('keydown',onKey,true);
    document.addEventListener('focusin',containFocus,true);
    let media;
    if(window.gsap?.matchMedia){
      media=window.gsap.matchMedia();
      media.add('(prefers-reduced-motion: no-preference)',()=>{
        window.gsap.fromTo(panel,{opacity:0,x:kind==='drawer'?20:0,y:kind==='dialog'?8:0},{opacity:1,x:0,y:0,duration:.2,ease:'power3.out',clearProps:'transform,opacity'});
      },host);
      cancelEnterRef.current=()=>media.revert();
    }
    return()=>{
      mountedRef.current=false;
      cancelExit();
      cancelEnterRef.current=null;
      media?.revert();
      document.removeEventListener('keydown',onKey,true);
      document.removeEventListener('focusin',containFocus,true);
      previous.forEach(({node,inert,aria})=>{node.inert=inert;if(aria===null)node.removeAttribute('aria-hidden');else node.setAttribute('aria-hidden',aria);});
      if(sharedLayer){sharedLayer.release();layerRef.current=null;}
      else document.body.style.overflow=previousOverflow;
      host.remove();
      const restoreTarget=restoreFocusRef.current?.current || previousFocus;
      if(!sharedLayer && restoreTarget?.isConnected && !restoreTarget.closest('[inert]'))restoreTarget.focus?.({preventScroll:true});
    };
  },[host,kind,requestClose,cancelExit]);
  // A save can start while a user-requested exit is still in flight.
  React.useLayoutEffect(()=>{
    if(busy && closingRef.current)cancelExit();
  },[busy,cancelExit]);
  // An async result can replace a focused footer button without closing the layer.
  React.useLayoutEffect(()=>{
    const panel=panelRef.current;
    const isTop=layerRef.current ? layerRef.current.isTop() : !panel?.closest('[inert]');
    if(isTop && panel?.isConnected && !panel.contains(document.activeElement))panel.focus({preventScroll:true});
  });
  return ReactDOM.createPortal(<div className={`bp-backdrop bp-backdrop--${kind}`}>
    <section ref={panelRef} className={`blueprint-v5 bp-layer bp-layer--${kind} ${className}`} role="dialog" aria-modal="true" aria-labelledby={labelId} aria-describedby={description?descriptionId:undefined} aria-busy={busy || undefined} tabIndex={-1}>
      <header className="bp-layer__header"><div><h2 id={labelId}>{title}</h2>{description && <p id={descriptionId}>{description}</p>}</div><button type="button" className="bp-close" disabled={busy} onClick={requestClose} aria-label={`关闭${title}`}><span aria-hidden="true">×</span></button></header>
      <div className="bp-layer__body">{children}</div>
      {footer && <footer className="bp-layer__footer">{footer}</footer>}
    </section>
  </div>,host);
}
function BlueprintDialog({open=true,...props}) {return open?<BlueprintLayer kind="dialog" {...props}/>:null;}
function BlueprintDrawer({open=true,...props}) {return open?<BlueprintLayer kind="drawer" {...props}/>:null;}
window.BlueprintAsset=BlueprintAsset;
window.BlueprintDialog=BlueprintDialog;
window.BlueprintDrawer=BlueprintDrawer;
