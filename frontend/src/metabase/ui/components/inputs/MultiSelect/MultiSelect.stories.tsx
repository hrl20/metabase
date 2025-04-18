import { MultiSelect, type MultiSelectProps, Stack } from "metabase/ui";

const dataWithGroupsLarge = [
  {
    group: "Overall row",
    items: [
      { value: "10", label: "Entity key", icon: "label" },
      { value: "11", label: "Entity name", icon: "string" },
      {
        value: "12",
        label: "Foreign key",
        icon: "connections",
      },
    ],
  },
  {
    group: "Common",
    items: [
      { value: "13", label: "Category", icon: "string" },
      {
        value: "14",
        label: "Comment",
        icon: "string",
        disabled: true,
      },
      { value: "15", label: "Description", icon: "string" },
      { value: "16", label: "Title", icon: "string" },
    ],
  },
  {
    group: "Location",
    items: [
      { value: "17", label: "City", icon: "location" },
      { value: "18", label: "Country", icon: "location" },
      { value: "19", label: "Latitude", icon: "location" },
      { value: "20", label: "Longitude", icon: "location" },
      { value: "21", label: "Longitude", icon: "location" },
      { value: "22", label: "State", icon: "location" },
      { value: "23", label: "Zip code", icon: "location" },
    ],
  },
];

const dataWithGroups = dataWithGroupsLarge;

const dataWithIcons = dataWithGroupsLarge.flatMap((group) => group.items);

const dataWithLabels = dataWithIcons.map((item) => ({
  ...item,
  icon: undefined,
}));

const args = {
  data: dataWithLabels,
  size: "md",
  label: "Field type",
  description: undefined,
  error: undefined,
  placeholder: "No semantic type",
  searchable: false,
  creatable: false,
  disabled: false,
  readOnly: false,
  withAsterisk: false,
  dropdownPosition: "flip",
};

const sampleArgs = {
  value: [dataWithLabels[0].value],
  description: "Determines how Metabase displays the field",
  error: "required",
};

const argTypes = {
  data: {
    control: { type: "json" },
  },
  size: {
    options: ["xs", "md"],
    control: { type: "inline-radio" },
  },
  label: {
    control: { type: "text" },
  },
  description: {
    control: { type: "text" },
  },
  error: {
    control: { type: "text" },
  },
  placeholder: {
    control: { type: "text" },
  },
  searchable: {
    control: { type: "boolean" },
  },
  creatable: {
    control: { type: "boolean" },
  },
  disabled: {
    control: { type: "boolean" },
  },
  readOnly: {
    control: { type: "boolean" },
  },
  withAsterisk: {
    control: { type: "boolean" },
  },
  dropdownPosition: {
    options: ["bottom", "top", "flip"],
    control: { type: "inline-radio" },
  },
};

const VariantTemplate = (args: MultiSelectProps) => (
  <Stack>
    <MultiSelect {...args} />
    <MultiSelect {...args} variant="unstyled" />
  </Stack>
);

export default {
  title: "Components/Inputs/MultiSelect",
  component: MultiSelect,
  args,
  argTypes,
};

export const Default = {};

export const EmptyMd = {
  render: VariantTemplate,
  name: "Empty, md",
};

export const AsteriskMd = {
  render: VariantTemplate,
  name: "Asterisk, md",
  args: {
    withAsterisk: true,
  },
};

export const ClearableMd = {
  render: VariantTemplate,
  name: "Clearable, md",
  args: {
    defaultValue: sampleArgs.value,
    clearable: true,
    withAsterisk: true,
  },
};

export const DescriptionMd = {
  render: VariantTemplate,
  name: "Description, md",
  args: {
    description: sampleArgs.description,
    withAsterisk: true,
  },
};

export const DisabledMd = {
  render: VariantTemplate,
  name: "Disabled, md",
  args: {
    description: sampleArgs.description,
    disabled: true,
    withAsterisk: true,
  },
};

export const ErrorMd = {
  render: VariantTemplate,
  name: "Error, md",
  args: {
    description: sampleArgs.description,
    error: sampleArgs.error,
    withAsterisk: true,
  },
};

export const ReadOnlyMd = {
  render: VariantTemplate,
  name: "Read only, md",
  args: {
    defaultValue: sampleArgs.value,
    description: sampleArgs.description,
    readOnly: true,
    withAsterisk: true,
  },
};

export const IconsMd = {
  render: VariantTemplate,
  name: "Icons, md",
  args: {
    data: dataWithIcons,
    description: sampleArgs.description,
    withAsterisk: true,
  },
};

export const GroupsMd = {
  render: VariantTemplate,
  name: "Groups, md",
  args: {
    data: dataWithGroups,
    description: sampleArgs.description,
    withAsterisk: true,
  },
};

export const LargeSetsMd = {
  render: VariantTemplate,
  name: "Large sets, md",
  args: {
    data: dataWithGroupsLarge,
    description: sampleArgs.description,
    withAsterisk: true,
  },
};

export const SearchableMd = {
  render: VariantTemplate,
  name: "Searchable, md",
  args: {
    data: dataWithGroupsLarge,
    description: sampleArgs.description,
    searchable: true,
    withAsterisk: true,
  },
};

export const CreatableMd = {
  render: VariantTemplate,
  name: "Creatable, md",
  args: {
    data: dataWithGroupsLarge,
    description: sampleArgs.description,
    getCreateLabel: (query: string) => `New ${query}`,
    creatable: true,
    searchable: true,
    withAsterisk: true,
  },
};

export const EmptyXs = {
  render: VariantTemplate,
  name: "Empty, xs",
  args: {
    size: "xs",
  },
};

export const AsteriskXs = {
  render: VariantTemplate,
  name: "Asterisk, xs",
  args: {
    ...AsteriskMd.args,
    size: "xs",
  },
};

export const ClearableXs = {
  render: VariantTemplate,
  name: "Clearable, xs",
  args: {
    ...ClearableMd.args,
    size: "xs",
  },
};

export const DescriptionXs = {
  render: VariantTemplate,
  name: "Description, xs",
  args: {
    ...DescriptionMd.args,
    size: "xs",
  },
};

export const DisabledXs = {
  render: VariantTemplate,
  name: "Disabled, xs",
  args: {
    ...DisabledMd.args,
    size: "xs",
  },
};

export const ErrorXs = {
  render: VariantTemplate,
  name: "Error, xs",
  args: {
    ...ErrorMd.args,
    size: "xs",
  },
};

export const ReadOnlyXs = {
  render: VariantTemplate,
  name: "Read only, xs",
  args: {
    ...ReadOnlyMd.args,
    size: "xs",
  },
};

export const IconsXs = {
  render: VariantTemplate,
  name: "Icons, xs",
  args: {
    ...IconsMd.args,
    size: "xs",
  },
};

export const GroupsXs = {
  render: VariantTemplate,
  name: "Groups, xs",
  args: {
    ...GroupsMd.args,
    size: "xs",
  },
};

export const LargeSetsXs = {
  render: VariantTemplate,
  name: "Large sets, xs",
  args: {
    ...LargeSetsMd.args,
    size: "xs",
  },
};

export const SearchableXs = {
  render: VariantTemplate,
  name: "Searchable, xs",
  args: {
    ...SearchableMd.args,
    size: "xs",
  },
};

export const CreatableXs = {
  render: VariantTemplate,
  name: "Creatable, xs",
  args: {
    ...CreatableMd.args,
    size: "xs",
  },
};
