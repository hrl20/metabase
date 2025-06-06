import { t } from "ttag";

import { ErrorBox } from "metabase/components/ErrorDetails";
import Modal from "metabase/components/Modal";
import ModalContent from "metabase/components/ModalContent";
import { Text } from "metabase/ui";

// OSS Component, do not use directly, use through PLUGIN_UPLOAD_MANAGEMENT
export const _FileUploadErrorModal = ({
  onClose,
  fileName,
  children,
}: {
  onClose: () => void;
  fileName?: string;
  children: string;
}) => {
  return (
    <Modal small>
      <ModalContent title={t`Upload error details`} onClose={onClose}>
        {fileName && (
          <Text>{t`There were some errors while uploading ${fileName}:`}</Text>
        )}
        <ErrorBox>{children}</ErrorBox>
      </ModalContent>
    </Modal>
  );
};
