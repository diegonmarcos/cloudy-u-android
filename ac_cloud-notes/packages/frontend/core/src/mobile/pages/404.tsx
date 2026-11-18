import { useLocation } from 'react-router-dom';

// #1717 the page also says where it is: the path the view's router tried (view) and the browser's own (browser), so a
// launch that lands here can be diagnosed from one screenshot.
export const Component = () => {
  const location = useLocation();
  return (
    <div>
      /404
      <div data-testid="not-found-where" style={{ opacity: 0.6, fontSize: 12 }}>
        {`view=${location.pathname}${location.search} browser=${window.location.pathname}`}
      </div>
    </div>
  );
};
