const MESSAGES: Record<string, string> = {
  CAMERA_PERMISSION_DENIED: 'Camera access is needed to scan documents. Enable it in Settings > Apps > PaperRescue > Permissions.',
  CAMERA_UNAVAILABLE: "We couldn't access the camera. Close other camera apps and try again.",
  CAPTURE_FAILED: "The photo couldn't be captured. Please try again.",
  PROCESSING_FAILED: "Something went wrong while processing the scan. Please try again.",
  E_OPENCV_NOT_READY: 'The scanning engine failed to start. Try restarting the app.',
  E_PROCESSING_FAILED: "Something went wrong while processing this page.",
  E_RESCUE_FAILED: "Rescue Scan couldn't finish processing this page. Please try again.",
  E_OCR_FAILED: "Text recognition failed for this page.",
  E_PDF_FAILED: "The PDF couldn't be created. Please try again.",
  E_SHARE_FAILED: "Sharing failed. Make sure you have an app installed that can receive this file.",
  E_NO_ACTIVITY: 'PaperRescue needs to be in the foreground to do this.',
  E_NO_PICKER: 'No photo gallery app is available on this device.',
  E_IMPORT_FAILED: 'Some photos could not be imported.',
  E_IO: 'A storage error occurred.',
  E_FILE_NOT_FOUND: 'That file could not be found — it may have been moved or deleted.',
  E_BUSY: 'Please wait for the current action to finish.',
  E_INVALID_ARGS: 'Something about this request was invalid.',
};

export function friendlyErrorMessage(error: unknown): string {
  const code = (error as { code?: string } | undefined)?.code;
  if (code && MESSAGES[code]) return MESSAGES[code];
  const message = (error as { message?: string } | undefined)?.message;
  if (message && message.length < 140) return message;
  return 'Something went wrong. Please try again.';
}

export function errorCode(error: unknown): string | undefined {
  return (error as { code?: string } | undefined)?.code;
}
